package com.glucotrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glucotrack.ScanStatus
import com.glucotrack.analysis.GlucoseStatistics
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.SensorRecord
import com.glucotrack.sensor.SensorState

@Composable
fun NowScreen(
    readings: List<GlucoseReading>,
    sensor: SensorRecord?,
    scanStatus: ScanStatus,
    armedToActivate: Boolean,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    now: Long,
    onArmActivation: () -> Unit,
    onCancelActivation: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            armedToActivate -> ActivationArmedCard(onCancelActivation)
            scanStatus is ScanStatus.Reading -> InfoCard("Reading sensor", "Keep the phone still.")
            scanStatus is ScanStatus.Error ->
                InfoCard("Scan failed", scanStatus.message, onDismiss = onDismissStatus)
            scanStatus is ScanStatus.Success &&
                scanStatus.scan.state == SensorState.NOT_ACTIVATED ->
                NotActivatedCard(onArmActivation)
            scanStatus is ScanStatus.Success &&
                scanStatus.scan.state == SensorState.WARMING_UP ->
                InfoCard(
                    "Sensor warming up",
                    "A new sensor needs about an hour before it reports glucose.",
                )
        }

        CurrentReadingCard(readings, targets, unit, now)
        SensorCard(sensor, now)

        val windowStart = now - 8 * 60 * 60 * 1000L
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Last 8 hours", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                GlucoseChart(
                    readings = readings.filter { it.timestamp >= windowStart },
                    targets = targets,
                    unit = unit,
                    windowStart = windowStart,
                    windowEnd = now,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
        }

        Text(
            "Hold the top of the phone against the sensor to take a reading. " +
                "Readings between scans are filled in from the sensor's own 8-hour memory.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CurrentReadingCard(
    readings: List<GlucoseReading>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    now: Long,
) {
    val latest = readings.maxByOrNull { it.timestamp }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            if (latest == null) {
                Text("No readings yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scan the sensor to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val band = targets.classify(latest.mgdl)
            val stale = now - latest.timestamp > 20 * 60 * 1000L

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    unit.format(latest.mgdl),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    color = BandColors.of(band),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(unit.label, style = MaterialTheme.typography.bodyMedium)
                    // Rate is computed from the stored trace, so it survives app restarts.
                    val rate = recentRate(readings, latest.timestamp)
                    Text(
                        trendArrow(rate),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }

            Text(
                bandLabel(band),
                style = MaterialTheme.typography.titleMedium,
                color = BandColors.of(band),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatElapsed(latest.timestamp, now) +
                    if (stale) " — scan again for a current reading" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recentRate(readings, latest.timestamp)?.let {
                Text(
                    formatRate(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Least-squares slope over the 20 minutes before [at], in mg/dL per minute. */
private fun recentRate(readings: List<GlucoseReading>, at: Long): Double? {
    val window = readings.filter { it.timestamp in (at - 20 * 60_000L)..at }
    if (window.size < 3) return null
    val xs = window.map { it.timestamp / 60_000.0 }
    val ys = window.map { it.mgdl }
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

private fun bandLabel(band: com.glucotrack.analysis.GlucoseBand) = when (band) {
    com.glucotrack.analysis.GlucoseBand.LOW -> "Below target range"
    com.glucotrack.analysis.GlucoseBand.IN_RANGE -> "In target range"
    com.glucotrack.analysis.GlucoseBand.HIGH -> "Above target range"
}

@Composable
private fun SensorCard(sensor: SensorRecord?, now: Long) {
    if (sensor == null) return
    val ageMinutes = ((now - sensor.activatedAt) / 60_000L).toInt()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sensor ${sensor.serial}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                formatSensorDay(ageMinutes, sensor.maxLifeMinutes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                formatSensorRemaining(ageMinutes, sensor.maxLifeMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Last scanned ${formatElapsed(sensor.lastScanAt, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, onDismiss: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun NotActivatedCard(onArm: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("This sensor hasn't been started", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Starting it begins its 14-day life and can't be undone. It will take about an " +
                    "hour to warm up before it reports glucose.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onArm) { Text("Start this sensor") }
        }
    }
}

@Composable
private fun ActivationArmedCard(onCancel: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Ready to start the sensor", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Hold the phone against the sensor now. The next tap will start it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onCancel) { Text("Cancel") }
        }
    }
}
