package com.glucotrack.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val dayTimeFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
fun formatDay(millis: Long): String = dayFormat.format(Date(millis))
fun formatDayTime(millis: Long): String = dayTimeFormat.format(Date(millis))

/** "just now", "12 min ago", "3 h 20 min ago" — how stale a reading is, in words. */
fun formatElapsed(fromMillis: Long, nowMillis: Long): String {
    val minutes = ((nowMillis - fromMillis) / 60_000L).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes == 1 -> "1 min ago"
        minutes < 60 -> "$minutes min ago"
        else -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "$h h ago" else "$h h $m min ago"
        }
    }
}

/**
 * The sensor's nominal wear period in whole days — the number printed on the box.
 *
 * Sensors report a maximum life a little beyond their nominal period: a Libre 2 sold as a
 * 14-day sensor reports rather more than 14 days, a grace margin past the advertised end.
 * Flooring gives the number the user actually recognises.
 */
private fun sensorTotalDays(maxLifeMinutes: Int): Int =
    (maxLifeMinutes / (24 * 60)).coerceAtLeast(1)

/** Sensor wear time expressed the way the box does: "Day 4 of 14". */
fun formatSensorDay(ageMinutes: Int, maxLifeMinutes: Int): String {
    val total = sensorTotalDays(maxLifeMinutes)
    val day = (ageMinutes / (24 * 60) + 1).coerceIn(1, total)
    return "Day $day of $total"
}

/**
 * How much of the wear period is left.
 *
 * Counted against the nominal period rather than the sensor's reported maximum, so this can
 * never claim more time remaining than the sensor is labelled to last — which is what it did
 * when it read the raw maximum, producing "Day 1 of 14" beside "14 days 9 hours left".
 *
 * The grace margin past the nominal end is deliberately not counted. A replacement should be
 * planned for the date on the box, not for however long the firmware happens to tolerate.
 */
fun formatSensorRemaining(ageMinutes: Int, maxLifeMinutes: Int): String {
    val nominalMinutes = sensorTotalDays(maxLifeMinutes) * 24 * 60
    val remaining = (nominalMinutes - ageMinutes).coerceIn(0, nominalMinutes)
    if (remaining == 0) return "Expired \u2014 time to replace it"

    val days = remaining / (24 * 60)
    val hours = remaining % (24 * 60) / 60
    fun plural(n: Int, unit: String) = "$n $unit" + if (n == 1) "" else "s"

    return when {
        days == 0 && hours == 0 -> "Less than an hour left"
        days == 0 -> "About ${plural(hours, "hour")} left"
        else -> "About ${plural(days, "day")} ${plural(hours, "hour")} left"
    }
}

/** Renders a rate of change as an arrow, using the thresholds clinicians read these by. */
fun trendArrow(mgdlPerMinute: Double?): String = when {
    mgdlPerMinute == null -> "–"
    mgdlPerMinute > 3.0 -> "↑↑"
    mgdlPerMinute > 2.0 -> "↑"
    mgdlPerMinute > 1.0 -> "↗"
    mgdlPerMinute > -1.0 -> "→"
    mgdlPerMinute > -2.0 -> "↘"
    mgdlPerMinute > -3.0 -> "↓"
    else -> "↓↓"
}

fun formatRate(mgdlPerMinute: Double?): String {
    if (mgdlPerMinute == null) return ""
    val perTenMin = mgdlPerMinute * 10
    val sign = if (perTenMin >= 0) "+" else "-"
    return "$sign${abs(perTenMin).roundToInt()} mg/dL per 10 min"
}
