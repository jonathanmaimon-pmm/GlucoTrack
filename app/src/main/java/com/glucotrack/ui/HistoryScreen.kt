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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glucotrack.analysis.GlucoseStatistics
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.NutritionEntry
import java.util.Calendar

@Composable
fun HistoryScreen(
    readings: List<GlucoseReading>,
    meals: List<NutritionEntry>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    now: Long,
    modifier: Modifier = Modifier,
) {
    // 0 is today, 1 is yesterday, and so on.
    var daysBack by remember { mutableIntStateOf(0) }
    val dayStart = remember(daysBack, now) { startOfDay(now) - daysBack * DAY_MILLIS }
    val dayEnd = dayStart + DAY_MILLIS

    val dayReadings = remember(readings, dayStart) {
        readings.filter { it.timestamp in dayStart until dayEnd }
    }
    val dayMeals = remember(meals, dayStart) {
        meals.filter { it.timestamp in dayStart until dayEnd }.sortedBy { it.timestamp }
    }
    val stats = remember(dayReadings, targets) {
        GlucoseStatistics.summarise(dayReadings, targets)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ daysBack += 1 }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous day")
            }
            Text(
                if (daysBack == 0) "Today" else formatDay(dayStart),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton({ daysBack -= 1 }, enabled = daysBack > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next day")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                GlucoseChart(
                    readings = dayReadings,
                    targets = targets,
                    unit = unit,
                    windowStart = dayStart,
                    windowEnd = dayEnd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                )
            }
        }

        StatsCard(stats, unit, "This day")

        if (dayMeals.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Food logged", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    dayMeals.forEach { meal ->
                        Text(
                            "${formatTime(meal.timestamp)}  ${meal.description}" +
                                (meal.carbsGrams?.let { " · ${it}g" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
