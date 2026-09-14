package com.glucotrack.analysis

import com.glucotrack.data.GlucoseReading
import kotlin.math.sqrt

/**
 * Summary of a window of readings.
 *
 * Percentages are shares of *time*, not of samples — see [GlucoseStatistics.summarise].
 */
data class GlucoseStats(
    val sampleCount: Int,
    val averageMgdl: Double?,
    val minMgdl: Double?,
    val maxMgdl: Double?,
    val standardDeviation: Double?,
    val timeInRangePercent: Double,
    val timeAbovePercent: Double,
    val timeBelowPercent: Double,
    /** Total time the readings actually cover, which is less than the window when scans are sparse. */
    val coveredMillis: Long,
) {
    /** Glucose variability. Below 36% is the usual stability benchmark. */
    val coefficientOfVariation: Double?
        get() {
            val avg = averageMgdl ?: return null
            val sd = standardDeviation ?: return null
            return if (avg == 0.0) null else sd / avg * 100
        }

    val hasData: Boolean get() = sampleCount > 0
}

object GlucoseStatistics {

    /**
     * A scan returns 1-minute trend samples and 15-minute history samples, so readings are not
     * evenly spaced. Anything longer than this between neighbours is treated as a gap in coverage
     * rather than as a long stretch at the last known value.
     */
    private const val MAX_GAP_MILLIS = 20 * 60 * 1000L

    /**
     * Summarises [readings], weighting each by the time it represents.
     *
     * Counting samples instead would badly skew the result: the sixteen 1-minute samples from the
     * most recent tap would carry the same weight as four hours of 15-minute history, making the
     * last quarter-hour dominate a whole day's statistics.
     */
    fun summarise(
        readings: List<GlucoseReading>,
        targets: GlucoseTargets = GlucoseTargets.PREGNANCY,
    ): GlucoseStats {
        if (readings.isEmpty()) {
            return GlucoseStats(0, null, null, null, null, 0.0, 0.0, 0.0, 0L)
        }

        val sorted = readings.sortedBy { it.timestamp }
        val values = sorted.map { it.mgdl }
        val average = values.average()
        val sd = if (values.size < 2) null else {
            sqrt(values.sumOf { (it - average) * (it - average) } / (values.size - 1))
        }

        val weights = timeWeights(sorted)
        val total = weights.sum()

        var low = 0.0
        var inRange = 0.0
        var high = 0.0
        sorted.forEachIndexed { i, reading ->
            when (targets.classify(reading.mgdl)) {
                GlucoseBand.LOW -> low += weights[i]
                GlucoseBand.IN_RANGE -> inRange += weights[i]
                GlucoseBand.HIGH -> high += weights[i]
            }
        }

        fun pct(part: Double) = if (total <= 0) 0.0 else part / total * 100

        return GlucoseStats(
            sampleCount = sorted.size,
            averageMgdl = average,
            minMgdl = values.min(),
            maxMgdl = values.max(),
            standardDeviation = sd,
            timeInRangePercent = pct(inRange),
            timeAbovePercent = pct(high),
            timeBelowPercent = pct(low),
            coveredMillis = total.toLong(),
        )
    }

    /**
     * Assigns each reading the span of time it stands for: half the gap to the previous reading
     * plus half the gap to the next, with each half capped so that a gap in scanning does not get
     * counted as time spent at whatever the last reading happened to be.
     */
    private fun timeWeights(sorted: List<GlucoseReading>): DoubleArray {
        if (sorted.size == 1) return doubleArrayOf(60_000.0)
        return DoubleArray(sorted.size) { i ->
            val before = if (i == 0) 0L else sorted[i].timestamp - sorted[i - 1].timestamp
            val after = if (i == sorted.lastIndex) 0L else sorted[i + 1].timestamp - sorted[i].timestamp
            val half = { gap: Long -> minOf(gap, MAX_GAP_MILLIS) / 2.0 }
            // Endpoints stand for one side only; mirror it so they are not under-weighted.
            when (i) {
                0 -> half(after) * 2
                sorted.lastIndex -> half(before) * 2
                else -> half(before) + half(after)
            }
        }
    }
}
