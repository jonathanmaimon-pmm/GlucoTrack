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
import com.glucotrack.SensorAlert
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
    sensorAlert: SensorAlert?,
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
        sensorAlert?.let { SensorAlertCard(it, now) }

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
            // Past this, the number describes the past rather than the present. "In target
            // range" is a claim about right now, and a reading this old cannot support it.
            val stale = now - latest.timestamp > STALE_AFTER_MILLIS

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    unit.format(latest.mgdl),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    // A stale value is drawn in muted grey, never in a band colour: colour here
                    // reads as a verdict on how things are, and that is exactly what an old
                    // reading cannot tell you.
                    color = if (stale) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        BandColors.of(band)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(unit.label, style = MaterialTheme.typography.bodyMedium)
                    if (!stale) {
                        // Rate comes from the stored trace, so it survives app restarts.
                        Text(
                            trendArrow(recentRate(readings, latest.timestamp)),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }

            if (stale) {
                Text(
                    "Out of date",
                    style = MaterialTheme.typography.titleMedium,
                    color = BandColors.high,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This reading is from ${formatElapsed(latest.timestamp, now)}. " +
                        "Hold the phone against the sensor for a current one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    bandLabel(band),
                    style = MaterialTheme.typography.titleMedium,
                    color = BandColors.of(band),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatElapsed(latest.timestamp, now),
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
}

/**
 * A sensor that has stopped, or reported a bad state.
 *
 * Placed above the reading because it changes what the reading means: the number below is not
 * current glucose, it is whatever the sensor last managed to record.
 */
@Composable
private fun SensorAlertCard(alert: SensorAlert, now: Long) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (alert) {
                is SensorAlert.Stopped -> {
                    Text(
                        "This sensor has stopped recording",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Its internal clock has not moved for " +
                            formatElapsed(now - alert.stillSinceMillis, now).removeSuffix(" ago") +
                            ", and still reads ${alert.ageMinutes} minutes since it was started. " +
                            "It keeps answering scans, but it is returning the last reading it " +
                            "managed to take rather than a current one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The glucose value below is not current. This sensor needs replacing.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is SensorAlert.BadState -> {
                    Text("Sensor reported a problem", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The sensor reports its state as ${alert.state}. Readings from it " +
                            "should not be relied on, and it likely needs replacing.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** How old a reading may be before it stops being presented as the current value. */
private const val STALE_AFTER_MILLIS = 15 * 60 * 1000L

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
