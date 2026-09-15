package com.glucotrack

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.data.AppSettings
import com.glucotrack.data.GlucoTrackDatabase
import com.glucotrack.data.GlucoTrackRepository
import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.NutritionEntry
import com.glucotrack.data.SensorRecord
import com.glucotrack.data.SettingsStore
import com.glucotrack.data.StreamingSession
import com.glucotrack.data.StreamingStore
import com.glucotrack.sensor.StreamingResult
import com.glucotrack.streaming.StreamingService
import com.glucotrack.data.Transfer
import com.glucotrack.sensor.NfcSensorReader
import com.glucotrack.sensor.ScanResult
import com.glucotrack.sensor.SensorScan
import com.glucotrack.sensor.SensorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One line of the scan log.
 *
 * Kept so consecutive scans can be compared. If the sensor's own clock does not advance between
 * two scans minutes apart, the sensor has stopped rather than the app -- a distinction nothing
 * else in the app can make.
 */
data class ScanLogEntry(
    val at: Long,
    val sensorAgeMinutes: Int,
    val rawValue: Int,
    val mgdl: Double?,
)

/** Outcome of an export or import, so the user is told what actually happened. */
sealed interface TransferStatus {
    data object Idle : TransferStatus
    data object Working : TransferStatus
    data class Exported(val readings: Int, val meals: Int) : TransferStatus
    data class Imported(val summary: Transfer.MergeSummary) : TransferStatus
    data class Failed(val message: String) : TransferStatus
}

/** Progress of an NFC tap, surfaced so the UI can say what is happening. */
sealed interface ScanStatus {
    data object Idle : ScanStatus
    data object Reading : ScanStatus
    data class Success(val scan: SensorScan, val at: Long) : ScanStatus
    data class Error(val message: String) : ScanStatus
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = GlucoTrackRepository(GlucoTrackDatabase.get(app))
    private val settingsStore = SettingsStore(app)
    private val streamingStore = StreamingStore(app)
    private val reader = NfcSensorReader()

    /** True while a tap is being processed. Always cleared in a finally block. */
    private val scanInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    /** Set when the next tap should switch on BLE streaming rather than take a reading. */
    private val _armedToEnableStreaming = MutableStateFlow(false)
    val armedToEnableStreaming: StateFlow<Boolean> = _armedToEnableStreaming.asStateFlow()

    val streamingSession: StateFlow<StreamingSession?> = streamingStore.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val streamState = StreamingService.state
    val lastStreamedAt = StreamingService.lastReadingAt

    /**
     * Whether the next tap should start an unstarted sensor rather than read it.
     *
     * Starting a sensor is irreversible — it begins the 14-day clock — so it never happens as a
     * side effect of a tap. The user has to arm it deliberately, and it disarms after one tap.
     */
    /**
     * The last successfully decoded scan, kept for the diagnostics view.
     *
     * Held in memory only. Its purpose is to expose the raw sensor numbers behind a reading so a
     * suspicious value can be checked against the calibration by hand, rather than taken on
     * trust.
     */
    private val _lastScan = MutableStateFlow<SensorScan?>(null)
    val lastScan: StateFlow<SensorScan?> = _lastScan.asStateFlow()

    /** The last few scans, newest first, for comparing one against the next. */
    private val _scanLog = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val scanLog: StateFlow<List<ScanLogEntry>> = _scanLog.asStateFlow()

    private val _armedToActivate = MutableStateFlow(false)
    val armedToActivate: StateFlow<Boolean> = _armedToActivate.asStateFlow()

    /** Bumped whenever stored data changes, plus once a minute so "x minutes ago" stays honest. */
    private val refresh = MutableStateFlow(System.currentTimeMillis())

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val sensor: StateFlow<SensorRecord?> = repository.currentSensor()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val meals: StateFlow<List<NutritionEntry>> = repository.nutritionEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recentReadings: StateFlow<List<GlucoseReading>> = refresh
        .flatMapLatest { now -> repository.readingsBetween(now - DAY_MILLIS, now + HOUR_MILLIS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allReadings: StateFlow<List<GlucoseReading>> = refresh
        .flatMapLatest { now ->
            repository.readingsBetween(now - 30L * DAY_MILLIS, now + HOUR_MILLIS)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ticks so elapsed-time labels and rolling windows stay current without user interaction. */
    val clock: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    init {
        viewModelScope.launch {
            clock.collect { refresh.value = it }
        }
    }

    /**
     * Handles a tag the activity's reader mode picked up.
     *
     * Reader mode is armed the whole time the app is in the foreground, so this fires simply from
     * holding the phone against the sensor — there is no button to find one-handed.
     */
    fun onTagDiscovered(tag: Tag) {
        // Guards against a second tap landing while one is in flight. It must never be able to
        // stay set: an earlier version left it set whenever anything threw, and because every
        // later tap returned here, the app went on showing the last reading it had managed to
        // take -- indefinitely, and with no sign that scanning had stopped working.
        if (!scanInFlight.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                _scanStatus.value = ScanStatus.Reading

                if (_armedToEnableStreaming.value) {
                    _armedToEnableStreaming.value = false
                    enableStreamingFromTap(tag)
                    return@launch
                }

                val activating = _armedToActivate.value
                val result = withTimeout(SCAN_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        if (activating) reader.activate(tag) else reader.read(tag)
                    }
                }
                _armedToActivate.value = false

                _scanStatus.value = when (result) {
                    is ScanResult.Failure -> ScanStatus.Error(result.reason)
                    is ScanResult.Success -> {
                        _lastScan.value = result.scan
                        _scanLog.value = (
                            listOf(
                                ScanLogEntry(
                                    at = result.scan.scannedAt,
                                    sensorAgeMinutes = result.scan.ageMinutes,
                                    rawValue = result.scan.trend.firstOrNull()?.rawValue ?: 0,
                                    mgdl = result.scan.current?.mgdl,
                                )
                            ) + _scanLog.value
                            ).take(6)
                        repository.saveScan(result.scan)
                        refresh.value = System.currentTimeMillis()
                        ScanStatus.Success(result.scan, System.currentTimeMillis())
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _scanStatus.value = ScanStatus.Error(
                    "The sensor didn't respond in time. Hold the phone still against it and try again."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Anything unexpected is reported rather than swallowed. Silence here is what
                // made the previous failure invisible.
                _scanStatus.value = ScanStatus.Error(
                    "Scan failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
                )
            } finally {
                _armedToActivate.value = false
                _armedToEnableStreaming.value = false
                scanInFlight.set(false)
            }
        }
    }

    fun armStreamingEnable() {
        _armedToEnableStreaming.value = true
    }

    fun cancelStreamingEnable() {
        _armedToEnableStreaming.value = false
    }

    /**
     * Turns on streaming during an NFC tap and remembers what a later connection will need.
     *
     * The unlock code is generated once here. Re-running this replaces it, which is exactly why
     * only one phone can stream: enabling it on a second device invalidates the first.
     */
    private suspend fun enableStreamingFromTap(tag: Tag) {
        val code = java.security.SecureRandom().nextInt(Int.MAX_VALUE)
        when (val result = withContext(Dispatchers.IO) { reader.enableStreaming(tag, code) }) {
            is StreamingResult.Failure -> _scanStatus.value = ScanStatus.Error(result.reason)
            is StreamingResult.Success -> {
                streamingStore.save(
                    StreamingSession(
                        macAddress = result.macAddress,
                        uid = result.uid,
                        patchInfo = result.patchInfo,
                        unlockCode = result.unlockCode,
                        unlockCount = 0,
                    )
                )
                _scanStatus.value = ScanStatus.Idle
                StreamingService.start(getApplication<Application>())
            }
        }
    }

    fun stopStreaming() {
        StreamingService.stop(getApplication<Application>())
        viewModelScope.launch { streamingStore.clear() }
    }

    fun restartStreaming() {
        StreamingService.start(getApplication<Application>())
    }

    fun armActivation() {
        _armedToActivate.value = true
    }

    fun cancelActivation() {
        _armedToActivate.value = false
    }

    fun dismissScanStatus() {
        _scanStatus.value = ScanStatus.Idle
    }

    fun addMeal(
        timestamp: Long,
        description: String,
        carbsGrams: Int?,
        mealType: com.glucotrack.data.MealType,
        notes: String?,
    ) {
        viewModelScope.launch {
            repository.saveNutritionEntry(
                NutritionEntry(
                    timestamp = timestamp,
                    description = description.trim(),
                    carbsGrams = carbsGrams,
                    mealType = mealType,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                )
            )
            refresh.value = System.currentTimeMillis()
        }
    }

    fun deleteMeal(id: Long) {
        viewModelScope.launch { repository.deleteNutritionEntry(id) }
    }

    private val _transferStatus = MutableStateFlow<TransferStatus>(TransferStatus.Idle)
    val transferStatus: StateFlow<TransferStatus> = _transferStatus.asStateFlow()

    /**
     * Builds the export text and hands it back for the UI to write and share.
     *
     * File writing stays in the UI layer because it needs a Context; the view model only owns
     * the data.
     */
    fun export(onReady: (String) -> Unit) {
        viewModelScope.launch {
            _transferStatus.value = TransferStatus.Working
            runCatching {
                val snapshot = repository.snapshot()
                val text = Transfer.export(snapshot, System.currentTimeMillis())
                _transferStatus.value =
                    TransferStatus.Exported(snapshot.readings.size, snapshot.meals.size)
                text
            }.onSuccess(onReady).onFailure {
                _transferStatus.value = TransferStatus.Failed(
                    it.message ?: "Couldn't build the export file."
                )
            }
        }
    }

    /** Merges an export from the other phone. Additive, and safe to repeat. */
    fun import(text: String) {
        viewModelScope.launch {
            _transferStatus.value = TransferStatus.Working
            runCatching { repository.merge(Transfer.parse(text)) }
                .onSuccess {
                    refresh.value = System.currentTimeMillis()
                    _transferStatus.value = TransferStatus.Imported(it)
                }
                .onFailure { e ->
                    _transferStatus.value = TransferStatus.Failed(
                        e.message ?: "That file couldn't be read."
                    )
                }
        }
    }

    fun dismissTransferStatus() {
        _transferStatus.value = TransferStatus.Idle
    }

    fun setUnit(unit: GlucoseUnit) {
        viewModelScope.launch { settingsStore.setUnit(unit) }
    }

    fun setTargets(targets: GlucoseTargets) {
        viewModelScope.launch { settingsStore.setTargets(targets) }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            repository.deleteEverything()
            refresh.value = System.currentTimeMillis()
        }
    }

    /** True when the last scan saw a sensor that has not been started yet. */
    val needsActivation: StateFlow<Boolean> =
        combine(scanStatus, sensor) { status, _ ->
            status is ScanStatus.Success && status.scan.state == SensorState.NOT_ACTIVATED
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private companion object {
        /** A tag exchange that has not finished by now is not going to. */
        const val SCAN_TIMEOUT_MS = 20_000L
        const val HOUR_MILLIS = 60 * 60 * 1000L
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}
