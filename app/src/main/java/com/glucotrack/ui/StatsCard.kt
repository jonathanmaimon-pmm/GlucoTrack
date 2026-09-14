package com.glucotrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.glucotrack.analysis.GlucoseStats
import com.glucotrack.analysis.GlucoseUnit
import kotlin.math.roundToInt

/** Summary statistics plus a time-in-range bar. */
@Composable
fun StatsCard(stats: GlucoseStats, unit: GlucoseUnit, title: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

            if (!stats.hasData) {
                Text(
                    "No readings in this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            TimeInRangeBar(stats)
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("In range", "${stats.timeInRangePercent.roundToInt()}%", BandColors.inRange)
                Stat("Above", "${stats.timeAbovePercent.roundToInt()}%", BandColors.high)
                Stat("Below", "${stats.timeBelowPercent.roundToInt()}%", BandColors.low)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("Average", stats.averageMgdl?.let { unit.format(it) } ?: "—")
                Stat("Lowest", stats.minMgdl?.let { unit.format(it) } ?: "—")
                Stat("Highest", stats.maxMgdl?.let { unit.format(it) } ?: "—")
                Stat(
                    "Variability",
                    stats.coefficientOfVariation?.let { "${it.roundToInt()}%" } ?: "—",
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Based on ${(stats.coveredMillis / 3_600_000.0).let { String.format("%.1f", it) }} " +
                    "hours of sensor coverage from ${stats.sampleCount} readings. " +
                    "Percentages are shares of covered time, not of scans.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeInRangeBar(stats: GlucoseStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        // Zero-width segments are skipped so a rounded bar does not show a sliver of a band
        // that never actually occurred.
        listOf(
            stats.timeBelowPercent to BandColors.low,
            stats.timeInRangePercent to BandColors.inRange,
            stats.timeAbovePercent to BandColors.high,
        ).filter { it.first > 0.01 }.forEach { (percent, color) ->
            Box(
                Modifier
                    .weight(percent.toFloat())
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
