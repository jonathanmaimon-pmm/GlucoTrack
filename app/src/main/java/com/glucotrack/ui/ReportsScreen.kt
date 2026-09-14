package com.glucotrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glucotrack.analysis.FastingAnalysis
import com.glucotrack.analysis.GlucoseStatistics
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.analysis.MealAnalysis
import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.NutritionEntry

private enum class Period(val label: String, val days: Int) {
    DAY("24 hours", 1),
    WEEK("7 days", 7),
    SENSOR("14 days", 14),
}

@Composable
fun ReportsScreen(
    readings: List<GlucoseReading>,
    meals: List<NutritionEntry>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    now: Long,
    modifier: Modifier = Modifier,
) {
    var period by remember { mutableStateOf(Period.WEEK) }
    val from = now - period.days * 24 * 60 * 60 * 1000L
    val windowReadings = remember(readings, from) { readings.filter { it.timestamp >= from } }
    val stats = remember(windowReadings, targets) {
        GlucoseStatistics.summarise(windowReadings, targets)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(p.label) },
                )
            }
        }

        StatsCard(stats, unit, "Last ${period.label}")

        FastingCard(windowReadings, targets, unit, now, period.days)
        MealImpactCard(meals, readings, targets, unit, from)

        Text(
            "These summaries describe what the sensor recorded on this phone. They are not a " +
                "clinical record, and gaps between scans are not filled in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FastingCard(
    readings: List<GlucoseReading>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    now: Long,
    days: Int,
) {
    val fasting = remember(readings, days, now) {
        FastingAnalysis.dailyFasting(readings, days.coerceAtLeast(2), now)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Morning readings", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sensor value nearest 07:00 — a stand-in for a fasting fingerstick, " +
                    "not a replacement for one. Target below ${unit.format(targets.fastingCeilingMgdl)} ${unit.label}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            fasting.forEach { day ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatDay(day.dayStartMillis), style = MaterialTheme.typography.bodyMedium)
                    val over = day.exceeds(targets)
                    Text(
                        day.mgdl?.let { unit.format(it) } ?: "no reading",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (over == true) FontWeight.Bold else FontWeight.Normal,
                        color = when (over) {
                            true -> BandColors.high
                            false -> MaterialTheme.colorScheme.onSurface
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MealImpactCard(
    meals: List<NutritionEntry>,
    readings: List<GlucoseReading>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    from: Long,
) {
    val ranked = remember(meals, readings, from) {
        MealAnalysis.rankByRise(
            MealAnalysis.analyse(meals.filter { it.timestamp >= from }, readings)
        ).take(5)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Biggest glucose rises after food", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (ranked.isEmpty()) {
                Text(
                    "Nothing to compare yet. Log what you eat and scan within two hours " +
                        "afterwards to see which foods matter most.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            ranked.forEach { impact ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            impact.entry.description,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            formatDayTime(impact.entry.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "+${unit.format(impact.riseMgdl!!)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (impact.exceededOneHourTarget(targets) == true) {
                            BandColors.high
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
