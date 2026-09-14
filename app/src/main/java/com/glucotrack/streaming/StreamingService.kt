package com.glucotrack.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.glucotrack.MainActivity
import com.glucotrack.R
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.data.GlucoTrackDatabase
import com.glucotrack.data.GlucoTrackRepository
import com.glucotrack.data.StreamingStore
import com.glucotrack.sensor.BleParser
import com.glucotrack.sensor.BleStreamClient
import com.glucotrack.sensor.CalibrationInfo
import com.glucotrack.sensor.Libre2Crypto
import com.glucotrack.sensor.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the Bluetooth connection alive while the app is in the background.
 *
 * Android will kill a plain background process holding a GATT connection, so collecting readings
 * unattended requires a foreground service and its permanent notification. That notification is
 * not decoration — it is the price of the OS leaving this running, and it doubles as an honest
 * signal: if it is gone, nothing is being collected.
 *
 * The service never alarms and never interprets a reading. It stores what arrives, and the rest
 * of the app treats streamed readings exactly like scanned ones.
 */
class StreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: GlucoTrackRepository
    private lateinit var store: StreamingStore
    private var client: BleStreamClient? = null

    override fun onCreate() {
        super.onCreate()
        repository = GlucoTrackRepository(GlucoTrackDatabase.get(this))
        store = StreamingStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            return START_NOT_STICKY
        }

        startForegroundCompat(notification("Waiting for the sensor"))
        startStreaming()
        // Restarted if the system kills us: an interrupted stream should resume on its own.
        return START_STICKY
    }

    private fun startStreaming() {
        scope.launch {
            val session = store.session.first()
            if (session == null) {
                _state.value = StreamState.Failed("Streaming hasn't been set up yet.")
                stopSelf()
                return@launch
            }

            val sensor = repository.currentSensorOnce()
            if (sensor == null || sensor.calI2 == 0) {
                // Calibration only arrives over NFC, and a packet cannot be decoded without it.
                _state.value =
                    StreamState.Failed("Scan the sensor once over NFC before streaming.")
                stopSelf()
                return@launch
            }
            val calibration = CalibrationInfo(
                sensor.calI1, sensor.calI2, sensor.calI3,
                sensor.calI4, sensor.calI5, sensor.calI6,
            )

            val unlockCount = store.nextUnlockCount()

            client = BleStreamClient(
                context = this@StreamingService,
                nextUnlockCount = { store.nextUnlockCount() },
                onReading = { packet -> handlePacket(packet, session.uid, calibration, sensor.serial) },
                onState = { state ->
                    _state.value = state
                    updateNotification(state)
                },
            ).also { it.connect(session, unlockCount) }
        }
    }

    private fun handlePacket(
        packet: ByteArray,
        uid: ByteArray,
        calibration: CalibrationInfo,
        serial: String,
    ) {
        val payload = Libre2Crypto.decryptBle(uid, packet)
        if (payload == null) {
            // Failed its checksum. Dropping it is correct: a corrupted packet would decode into
            // a believable reading.
            return
        }
        val reading = BleParser.parse(payload, calibration, System.currentTimeMillis()) ?: return
        scope.launch {
            repository.saveBleReading(serial, reading)
            _lastReadingAt.value = System.currentTimeMillis()
            reading.current?.mgdl?.let { _lastMgdl.value = it }
            updateNotification(_state.value)
        }
    }

    private fun stopStreaming() {
        client?.disconnect()
        client = null
        _state.value = StreamState.Disconnected
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        client?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification ----

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sensor streaming",
            // Low importance: this notification exists to keep the service alive, not to
            // interrupt anyone.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while readings are being collected from the sensor."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GlucoTrack")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_glucose)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: StreamState) {
        val mgdl = _lastMgdl.value
        val text = when {
            state is StreamState.Failed -> state.reason
            state is StreamState.Listening && mgdl != null ->
                "Last reading ${GlucoseUnit.MGDL.format(mgdl)} mg/dL"
            state is StreamState.Listening -> "Connected, waiting for a reading"
            state is StreamState.Connecting -> "Waiting for the sensor"
            else -> "Not connected"
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "glucotrack_streaming"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.glucotrack.STOP_STREAMING"

        private val _state = MutableStateFlow<StreamState>(StreamState.Disconnected)
        val state: StateFlow<StreamState> = _state

        private val _lastReadingAt = MutableStateFlow<Long?>(null)
        val lastReadingAt: StateFlow<Long?> = _lastReadingAt

        private val _lastMgdl = MutableStateFlow<Double?>(null)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
