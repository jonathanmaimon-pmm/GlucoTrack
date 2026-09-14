package com.glucotrack.sensor

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Decodes a decrypted 344-byte Libre FRAM image into glucose measurements.
 *
 * FRAM layout (byte offsets):
 * ```
 *   0..23    header  (CRC at 0..1 over 2..23)
 *  24..319   body    (CRC at 24..25 over 26..319)
 * 320..343   footer  (CRC at 320..321 over 322..343)
 * ```
 * The body holds a 16-entry ring of 1-minute "trend" records and a 32-entry ring of 15-minute
 * "history" records, each record 6 bytes and bit-packed.
 */
object FramParser {

    private const val TREND_RECORDS = 16
    private const val HISTORY_RECORDS = 32
    private const val RECORD_SIZE = 6
    private const val TREND_OFFSET = 28
    private const val HISTORY_OFFSET = 124

    /**
     * FRAM is written ~3 minutes behind the sensor's live clock; the history pointer maths below
     * relies on this documented lag.
     */
    private const val FRAM_WRITE_DELAY_MINUTES = 3

    /**
     * Parses a FRAM image that is already in plaintext.
     *
     * @param scannedAt epoch millis the tap completed — all timestamps are derived backwards from
     *   this, since the sensor has no absolute clock of its own.
     */
    fun parse(
        uid: ByteArray,
        patchInfo: ByteArray,
        fram: ByteArray,
        scannedAt: Long,
    ): ScanResult {
        if (fram.size < Libre2Crypto.FRAM_SIZE) {
            return ScanResult.Failure("Only read ${fram.size} of ${Libre2Crypto.FRAM_SIZE} bytes. Hold the phone still against the sensor.")
        }

        val family = SensorFamily.fromPatchInfo(patchInfo)
        val serial = SensorSerial.from(uid, family)
        val state = SensorState.fromCode(fram[4].toInt() and 0xFF)

        if (!crcValid(fram)) {
            // A sensor that has not been started yet has no meaningful body to check.
            return if (state == SensorState.NOT_ACTIVATED) {
                ScanResult.Success(
                    SensorScan(
                        serial = serial,
                        family = family,
                        state = state,
                        ageMinutes = 0,
                        maxLifeMinutes = 0,
                        scannedAt = scannedAt,
                        trend = emptyList(),
                        history = emptyList(),
                        calibration = CalibrationInfo(),
                    )
                )
            } else {
                ScanResult.Failure("Sensor data failed its integrity check. Try scanning again.")
            }
        }

        val ageMinutes = u8(fram, 316) or (u8(fram, 317) shl 8)
        val maxLifeMinutes = u8(fram, 326) or (u8(fram, 327) shl 8)
        val calibration = readCalibration(fram)

        val startedAt = scannedAt - ageMinutes * 60_000L
        val trendIndex = u8(fram, 26)
        val historyIndex = u8(fram, 27)

        val trend = ArrayList<GlucoseSample>(TREND_RECORDS)
        for (i in 0 until TREND_RECORDS) {
            var j = trendIndex - 1 - i
            if (j < 0) j += TREND_RECORDS
            val id = ageMinutes - i
            trend += readRecord(
                fram = fram,
                offset = TREND_OFFSET + j * RECORD_SIZE,
                minutesSinceStart = id,
                timestamp = startedAt + id * 60_000L,
                calibration = calibration,
            )
        }

        // History records sit on a 15-minute boundary, offset by the FRAM write delay.
        val delay = (ageMinutes - FRAM_WRITE_DELAY_MINUTES) % 15 + FRAM_WRITE_DELAY_MINUTES

        val history = ArrayList<GlucoseSample>(HISTORY_RECORDS)
        for (i in 0 until HISTORY_RECORDS) {
            var j = historyIndex - 1 - i
            if (j < 0) j += HISTORY_RECORDS
            val id = ageMinutes - delay - i * 15
            // Records older than the sensor's own start are ring-buffer slots never yet written.
            if (id < 0) continue
            history += readRecord(
                fram = fram,
                offset = HISTORY_OFFSET + j * RECORD_SIZE,
                minutesSinceStart = id,
                // Derived from the sensor's own clock, exactly as trend samples are. An earlier
                // version anchored these to the scan time instead, which could place a history
                // record in the future -- and since the current reading is whichever sample is
                // newest, that let a 15-minute-old history value be shown as the live one.
                timestamp = startedAt + id * 60_000L,
                calibration = calibration,
            )
        }

        return ScanResult.Success(
            SensorScan(
                serial = serial,
                family = family,
                state = state,
                ageMinutes = ageMinutes,
                maxLifeMinutes = if (maxLifeMinutes > 0) maxLifeMinutes else 14 * 24 * 60,
                scannedAt = scannedAt,
                trend = trend,
                history = history,
                calibration = calibration,
            )
        )
    }

    private fun u8(fram: ByteArray, index: Int) = fram[index].toInt() and 0xFF

    /** Verifies the three independent CRC-protected regions of the FRAM. */
    fun crcValid(fram: ByteArray): Boolean {
        if (fram.size < Libre2Crypto.FRAM_SIZE) return false
        val header = u8(fram, 0) or (u8(fram, 1) shl 8)
        val body = u8(fram, 24) or (u8(fram, 25) shl 8)
        val footer = u8(fram, 320) or (u8(fram, 321) shl 8)
        return header == Crc16.compute(fram, 2, 22) &&
            body == Crc16.compute(fram, 26, 294) &&
            footer == Crc16.compute(fram, 322, 22)
    }

    private fun readCalibration(fram: ByteArray): CalibrationInfo {
        val i3 = readBits(fram, 0x150, 0, 8)
        val negativeI3 = readBits(fram, 0x150, 0x21, 1) != 0
        return CalibrationInfo(
            i1 = readBits(fram, 2, 0, 3),
            i2 = readBits(fram, 2, 3, 0xA),
            i3 = if (negativeI3) -i3 else i3,
            i4 = readBits(fram, 0x150, 8, 0xE),
            i5 = readBits(fram, 0x150, 0x28, 0xC) shl 2,
            i6 = readBits(fram, 0x150, 0x34, 0xC) shl 2,
        )
    }

    /** Unpacks one 6-byte bit-packed measurement record. */
    private fun readRecord(
        fram: ByteArray,
        offset: Int,
        minutesSinceStart: Int,
        timestamp: Long,
        calibration: CalibrationInfo,
    ): GlucoseSample {
        val rawValue = readBits(fram, offset, 0, 0xE)
        val qualityBits = readBits(fram, offset, 0xE, 0xB)
        val hasError = readBits(fram, offset, 0x19, 0x1) != 0
        val rawTemperature = readBits(fram, offset, 0x1A, 0xC) shl 2
        var temperatureAdjustment = readBits(fram, offset, 0x26, 0x9) shl 2
        if (readBits(fram, offset, 0x2F, 0x1) != 0) temperatureAdjustment = -temperatureAdjustment

        val mgdl = factoryGlucose(rawValue, rawTemperature, temperatureAdjustment, calibration)

        return GlucoseSample(
            minutesSinceStart = minutesSinceStart,
            timestamp = timestamp,
            mgdl = if (hasError || rawValue == 0) null else mgdl,
            rawValue = rawValue,
            rawTemperature = rawTemperature,
            temperatureAdjustment = temperatureAdjustment,
            hasError = hasError,
            dataQuality = qualityBits and 0x1FF,
        )
    }

    /**
     * Applies the sensor's factory calibration to a raw ADC count.
     *
     * The raw value is temperature-dependent, so this first reconstructs the sensor's thermistor
     * temperature, then compensates the glucose count for it before applying the per-sensor
     * linear fit held in [calibration].
     *
     * Returns null when the sensor's own parameters are out of range, rather than guessing —
     * a wrong number here is worse than no number.
     */
    fun factoryGlucose(
        rawValue: Int,
        rawTemperature: Int,
        temperatureAdjustment: Int,
        calibration: CalibrationInfo,
    ): Double? {
        if (rawValue <= 0 || !calibration.isUsable) return null

        val denominator = temperatureAdjustment + calibration.i6
        if (denominator == 0) return null

        // Thermistor resistance, then Steinhart-Hart to degrees Celsius.
        val r = (rawTemperature.toDouble() * (1000 + 71500)) / denominator - 1000
        if (r <= 0) return null
        val logR = ln(r)
        val d = logR.pow(3) * 0.00000005283566 +
            logR.pow(2) * 0.0000007061775 +
            logR * 0.0001964561 +
            0.0009180023
        if (d == 0.0) return null
        val temperature = 1 / d - 273.15

        val g1 = 65 * (rawValue - calibration.i3).toDouble() / (calibration.i4 - calibration.i3)
        val g2 = 1.045.pow(32.5 - temperature)
        val g3 = g1 * g2

        val v1 = CalibrationTables.T1[calibration.i2 - 1]
        val v2 = CalibrationTables.T2[calibration.i2 - 1]
        if (v2 == 0.0) return null

        val value = ((g3 - v1) / v2).roundToInt().toDouble()
        // Outside this window the sensor itself is not specified; treat as unusable.
        return if (value in 20.0..500.0) value else null
    }
}
