package com.glucotrack.sensor

/** Lifecycle state reported by the sensor in FRAM byte 4. */
enum class SensorState(val code: Int) {
    UNKNOWN(0),
    NOT_ACTIVATED(1),
    WARMING_UP(2),
    ACTIVE(3),
    EXPIRED(4),
    SHUT_DOWN(5),
    FAILURE(6);

    companion object {
        fun fromCode(code: Int): SensorState = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Sensor product family, derived from the high nibble of patch info byte 2. */
enum class SensorFamily(val code: Int) {
    LIBRE1(0),
    LIBRE_PRO(1),
    LIBRE2(3),
    LIBRE3(4),
    UNKNOWN(-1);

    companion object {
        fun fromPatchInfo(patchInfo: ByteArray): SensorFamily {
            if (patchInfo.size < 3) return UNKNOWN
            val code = (patchInfo[2].toInt() and 0xFF) shr 4
            return entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}

/**
 * Per-sensor factory calibration parameters, read from the FRAM header and footer.
 *
 * These are baked in at manufacture and are what make the sensor "factory calibrated" — they turn
 * a raw ADC count into a glucose concentration without any fingerstick calibration.
 */
data class CalibrationInfo(
    val i1: Int = 0,
    val i2: Int = 0,
    val i3: Int = 0,
    val i4: Int = 0,
    val i5: Int = 0,
    val i6: Int = 0,
) {
    /** The lookup tables are indexed by `i2 - 1`, so i2 must land inside them. */
    val isUsable: Boolean get() = i2 in 1..CalibrationTables.T1.size && i4 != i3
}

/**
 * A single glucose measurement recovered from the sensor.
 *
 * @param minutesSinceStart the sensor's own clock, used as a stable identity for de-duplication
 *   across scans — the same measurement read twice carries the same value.
 * @param mgdl the calibrated reading, or null when the sensor flagged the sample as invalid.
 */
data class GlucoseSample(
    val minutesSinceStart: Int,
    val timestamp: Long,
    val mgdl: Double?,
    val rawValue: Int,
    val rawTemperature: Int,
    val temperatureAdjustment: Int,
    val hasError: Boolean,
    val dataQuality: Int,
) {
    val isValid: Boolean get() = mgdl != null && !hasError && rawValue > 0
}

/** Everything one NFC tap recovers from the sensor. */
data class SensorScan(
    val serial: String,
    val family: SensorFamily,
    val state: SensorState,
    /** Minutes since the sensor was activated. */
    val ageMinutes: Int,
    /** Total sensor lifetime in minutes, normally 14 days. */
    val maxLifeMinutes: Int,
    val scannedAt: Long,
    /** Up to 16 samples at 1-minute resolution, newest first. */
    val trend: List<GlucoseSample>,
    /** Up to 32 samples at 15-minute resolution, newest first. */
    val history: List<GlucoseSample>,
    val calibration: CalibrationInfo,
) {
    /** The most recent usable reading, if the sensor produced one. */
    val current: GlucoseSample? get() = trend.firstOrNull { it.isValid }

    val minutesRemaining: Int get() = (maxLifeMinutes - ageMinutes).coerceAtLeast(0)

    /**
     * Rate of change in mg/dL per minute, from a least-squares fit over the last 15 minutes of
     * trend data. Null when there are too few valid samples to fit a line.
     */
    val trendRate: Double?
        get() {
            val pts = trend.filter { it.isValid }.take(15)
            if (pts.size < 3) return null
            val xs = pts.map { it.minutesSinceStart.toDouble() }
            val ys = pts.map { it.mgdl!! }
            val mx = xs.average()
            val my = ys.average()
            var num = 0.0
            var den = 0.0
            for (i in xs.indices) {
                num += (xs[i] - mx) * (ys[i] - my)
                den += (xs[i] - mx) * (xs[i] - mx)
            }
            return if (den == 0.0) null else num / den
        }
}

/** Outcome of decoding one NFC tap. */
sealed interface ScanResult {
    data class Success(val scan: SensorScan) : ScanResult
    /** [reason] is written for the person holding the phone, not for a log file. */
    data class Failure(val reason: String) : ScanResult
}
