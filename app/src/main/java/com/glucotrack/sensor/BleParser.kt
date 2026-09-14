package com.glucotrack.sensor

/** One decrypted BLE notification: ten measurements plus the sensor's own clock. */
data class BleReading(
    val wearTimeMinutes: Int,
    /** Seven recent measurements, newest first, at irregular one-minute offsets. */
    val trend: List<GlucoseSample>,
    /** The three most recent 15-minute history records, newest first. */
    val history: List<GlucoseSample>,
) {
    val current: GlucoseSample? get() = trend.firstOrNull { it.isValid }
}

/**
 * Decodes the 44-byte payload a Libre 2 broadcasts once a minute over BLE.
 *
 * The layout differs from FRAM: ten 4-byte records rather than six, and a narrower temperature
 * adjustment field. The first seven are recent trend samples at the fixed minute offsets in
 * [TREND_OFFSETS]; the last three repeat the newest 15-minute history records, which is what
 * lets a stream that drops for a few minutes heal itself without an NFC scan.
 *
 * Calibration is not carried in the packet, so [CalibrationInfo] from the last NFC scan must be
 * supplied. Without it there is no way to turn these raw counts into glucose.
 */
object BleParser {

    /** Minute offsets back from the sensor's current wear time for the seven trend samples. */
    private val TREND_OFFSETS = intArrayOf(0, 2, 4, 6, 7, 12, 15)

    /** History records are written two minutes behind the sensor's live clock. */
    private const val HISTORY_DELAY = 2

    private const val RECORD_SIZE = 4
    private const val TREND_COUNT = 7
    private const val TOTAL_RECORDS = 10

    /**
     * @param payload the 44 decrypted bytes from [Libre2Crypto.decryptBle]
     * @param receivedAt epoch millis the packet arrived; timestamps are derived backwards from it
     */
    fun parse(payload: ByteArray, calibration: CalibrationInfo, receivedAt: Long): BleReading? {
        if (payload.size < Libre2Crypto.BLE_PAYLOAD_SIZE) return null

        val wearTimeMinutes = (payload[40].toInt() and 0xFF) or ((payload[41].toInt() and 0xFF) shl 8)
        if (wearTimeMinutes <= 0) return null
        val startedAt = receivedAt - wearTimeMinutes * 60_000L

        val trend = ArrayList<GlucoseSample>(TREND_COUNT)
        val history = ArrayList<GlucoseSample>(TOTAL_RECORDS - TREND_COUNT)

        for (i in 0 until TOTAL_RECORDS) {
            val offset = i * RECORD_SIZE
            val rawValue = readBits(payload, offset, 0, 0xE)
            val rawTemperature = readBits(payload, offset, 0xE, 0xC) shl 2
            var temperatureAdjustment = readBits(payload, offset, 0x1A, 0x5) shl 2
            if (readBits(payload, offset, 0x1F, 0x1) != 0) {
                temperatureAdjustment = -temperatureAdjustment
            }

            val minutesSinceStart = if (i < TREND_COUNT) {
                wearTimeMinutes - TREND_OFFSETS[i]
            } else {
                // Snap to the 15-minute grid the history ring is written on.
                ((wearTimeMinutes - HISTORY_DELAY) / 15) * 15 - 15 * (i - TREND_COUNT)
            }
            if (minutesSinceStart < 0) continue

            // A raw value of zero means the sensor flagged the sample; the temperature field
            // then carries an error code rather than a temperature.
            val hasError = rawValue == 0
            val sample = GlucoseSample(
                minutesSinceStart = minutesSinceStart,
                timestamp = startedAt + minutesSinceStart * 60_000L,
                mgdl = if (hasError) null else FramParser.factoryGlucose(
                    rawValue, rawTemperature, temperatureAdjustment, calibration,
                ),
                rawValue = rawValue,
                rawTemperature = if (hasError) 0 else rawTemperature,
                temperatureAdjustment = temperatureAdjustment,
                hasError = hasError,
                dataQuality = if (hasError) (rawTemperature shr 2) and 0x1FF else 0,
            )

            if (i < TREND_COUNT) trend += sample else history += sample
        }

        return BleReading(wearTimeMinutes, trend, history)
    }
}
