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
import com.glucotrack.sensor.NfcSensorReader
import com.glucotrack.sensor.ScanResult
import com.glucotrack.sensor.SensorScan
import com.glucotrack.sensor.SensorState
import kotlinx.coroutines.Dispatchers
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
    private val reader = NfcSensorReader()

    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    /**
     * Whether the next tap should start an unstarted sensor rather than read it.
     *
     * Starting a sensor is irreversible — it begins the 14-day clock — so it never happens as a
     * side effect of a tap. The user has to arm it deliberately, and it disarms after one tap.
     */
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
        if (_scanStatus.value == ScanStatus.Reading) return
        viewModelScope.launch {
            _scanStatus.value = ScanStatus.Reading
            val activating = _armedToActivate.value
            val result = withContext(Dispatchers.IO) {
                if (activating) reader.activate(tag) else reader.read(tag)
            }
            _armedToActivate.value = false

            _scanStatus.value = when (result) {
                is ScanResult.Failure -> ScanStatus.Error(result.reason)
                is ScanResult.Success -> {
                    repository.saveScan(result.scan)
                    refresh.value = System.currentTimeMillis()
                    ScanStatus.Success(result.scan, System.currentTimeMillis())
                }
            }
        }
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
        const val HOUR_MILLIS = 60 * 60 * 1000L
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}
