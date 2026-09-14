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

/** Sensor wear time expressed the way the box does: "Day 4 of 14". */
fun formatSensorDay(ageMinutes: Int, maxLifeMinutes: Int): String {
    val day = ageMinutes / (24 * 60) + 1
    val total = maxLifeMinutes / (24 * 60)
    return "Day $day of $total"
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
