package com.glucotrack.analysis

import com.glucotrack.data.GlucoseReading
import java.util.Calendar
import kotlin.math.abs

/**
 * The overnight reading for one day.
 *
 * This is a *proxy* for a fasting fingerstick, not the same measurement: it is whatever the
 * sensor recorded nearest the target hour. The UI says so, because the fasting number is the one
 * most likely to be quoted to a clinician and it should not be passed off as something it isn't.
 */
data class FastingReading(
    val dayStartMillis: Long,
    val mgdl: Double?,
    val measuredAtMillis: Long?,
) {
    fun exceeds(targets: GlucoseTargets): Boolean? =
        mgdl?.let { it > targets.fastingCeilingMgdl }
}

object FastingAnalysis {

    /** Hour of day the overnight reading is taken from, and how far either side to look. */
    private const val TARGET_HOUR = 7
    private const val TOLERANCE_MILLIS = 2 * 60 * 60 * 1000L
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * One overnight reading per day for the last [days] days, newest first.
     *
     * Days with no sensor coverage near the target hour are included with a null value rather
     * than omitted, so a gap in scanning is visible instead of silently shortening the series.
     */
    fun dailyFasting(
        readings: List<GlucoseReading>,
        days: Int,
        now: Long,
    ): List<FastingReading> {
        if (days <= 0) return emptyList()
        val sorted = readings.sortedBy { it.timestamp }
        return (0 until days).map { back ->
            val target = targetInstant(now - back * DAY_MILLIS)
            val match = sorted
                .filter { abs(it.timestamp - target) <= TOLERANCE_MILLIS }
                .minByOrNull { abs(it.timestamp - target) }
            FastingReading(
                dayStartMillis = startOfDay(now - back * DAY_MILLIS),
                mgdl = match?.mgdl,
                measuredAtMillis = match?.timestamp,
            )
        }
    }

    private fun targetInstant(dayMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayMillis
        set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
