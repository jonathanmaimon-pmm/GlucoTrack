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
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.TransferStatus
import com.glucotrack.data.StreamingSession
import com.glucotrack.sensor.StreamState
import com.glucotrack.sensor.SensorScan
import com.glucotrack.ScanLogEntry
import androidx.compose.material3.Button
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun SettingsScreen(
    unit: GlucoseUnit,
    targets: GlucoseTargets,
    readingCount: Int,
    transferStatus: TransferStatus,
    streamingSession: StreamingSession?,
    streamState: StreamState,
    lastStreamedAt: Long?,
    armedToEnableStreaming: Boolean,
    lastScan: SensorScan?,
    scanLog: List<ScanLogEntry>,
    now: Long,
    onUnitChange: (GlucoseUnit) -> Unit,
    onTargetsChange: (GlucoseTargets) -> Unit,
    onArmStreaming: () -> Unit,
    onCancelStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onExport: ((String) -> Unit) -> Unit,
    onImport: (String) -> Unit,
    onDismissTransfer: () -> Unit,
    onDeleteEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Units", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlucoseUnit.entries.forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { onUnitChange(u) },
                            label = { Text(u.label) },
                        )
                    }
                }
            }
        }

        TargetsCard(targets, onTargetsChange)

        StreamingCard(
            session = streamingSession,
            state = streamState,
            lastStreamedAt = lastStreamedAt,
            armed = armedToEnableStreaming,
            now = now,
            onArm = onArmStreaming,
            onCancel = onCancelStreaming,
            onStop = onStopStreaming,
        )

        SharingCard(transferStatus, onExport, onImport, onDismissTransfer)

        DiagnosticsCard(lastScan, scanLog, now)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Your data", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$readingCount readings stored on this phone. This app has no internet " +
                        "permission at all, so nothing it records can leave the device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton({ confirmDelete = true }) { Text("Delete all data") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "GlucoTrack reads a FreeStyle Libre 2 over NFC and keeps the results on this " +
                        "phone. It is a personal tool, not a medical device, and it is not made " +
                        "by or connected to Abbott. Treatment decisions belong with the numbers " +
                        "your clinician asks you to use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "This removes every stored reading and food entry from this phone. " +
                        "It cannot be undone, and the readings cannot be recovered from the " +
                        "sensor beyond its own last 8 hours."
                )
            },
            confirmButton = {
                TextButton({
                    onDeleteEverything()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton({ confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TargetsCard(targets: GlucoseTargets, onChange: (GlucoseTargets) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Target range", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Defaults are the pregnancy targets, which are tighter than general diabetes " +
                    "ones. Change them to whatever your clinician has given you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Values are always entered in mg/dL to keep one unambiguous source of truth.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetField("Low", targets.lowMgdl) { onChange(targets.copy(lowMgdl = it)) }
                TargetField("High", targets.highMgdl) { onChange(targets.copy(highMgdl = it)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetField("Fasting", targets.fastingCeilingMgdl) {
                    onChange(targets.copy(fastingCeilingMgdl = it))
                }
                TargetField("1 h", targets.oneHourCeilingMgdl) {
                    onChange(targets.copy(oneHourCeilingMgdl = it))
                }
                TargetField("2 h", targets.twoHourCeilingMgdl) {
                    onChange(targets.copy(twoHourCeilingMgdl = it))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "All values in mg/dL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TargetField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toInt().toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(3)
            // Ignore implausible entries rather than storing a target that would mislabel
            // every reading on every screen.
            text.toIntOrNull()?.takeIf { it in 40..300 }?.let { onChange(it.toDouble()) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(96.dp),
    )
}


/**
 * Moving records between two phones.
 *
 * Both partners often end up with the app installed, and because nothing syncs, each phone only
 * holds the readings it personally collected. Neither has the full picture, yet both will happily
 * compute statistics over their own partial coverage. Exporting from one phone and importing on
 * the other reconciles them without involving a server.
 */
@Composable
private fun SharingCard(
    status: TransferStatus,
    onExport: ((String) -> Unit) -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.onSuccess(onImport)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sharing between phones", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Each phone only has the readings it scanned itself — nothing syncs on its own. " +
                    "Export from one phone and import on the other to bring them together. " +
                    "Importing only adds; it never removes anything, and importing the same " +
                    "file twice changes nothing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onExport { text ->
                        runCatching {
                            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                            val file = File(dir, "glucotrack-export.csv")
                            file.writeText(text)
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.exports", file,
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "Share GlucoTrack export",
                                )
                            )
                        }
                    }
                }) { Text("Export") }

                OutlinedButton(onClick = {
                    // Some file apps report exports as text/comma-separated-values or
                    // application/octet-stream, so the filter stays broad.
                    importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                }) { Text("Import") }
            }

            val message = when (status) {
                is TransferStatus.Working -> "Working..."
                is TransferStatus.Exported ->
                    "Exported ${status.readings} readings and ${status.meals} food entries."
                is TransferStatus.Imported -> with(status.summary) {
                    if (nothingNew) {
                        "Nothing new — this phone already had everything in that file."
                    } else {
                        "Added $readingsAdded readings and $mealsAdded food entries. " +
                            "$readingsAlreadyPresent readings were already here."
                    }
                }
                is TransferStatus.Failed -> status.message
                TransferStatus.Idle -> null
            }

            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is TransferStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                TextButton(onDismiss) { Text("OK") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "An export leaves this phone once you pick an app to send it to. Anything " +
                    "beyond that is up to where you send it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/**
 * Continuous readings over Bluetooth.
 *
 * Streaming is additive: NFC scanning works identically whether this is on, off, or broken, so a
 * Bluetooth problem can never cost a reading that a tap would have caught.
 *
 * Only one phone can stream. The sensor accepts a single connection and enabling streaming
 * assigns a fresh unlock code, so switching it on here stops it working anywhere else.
 */
@Composable
private fun StreamingCard(
    session: StreamingSession?,
    state: StreamState,
    lastStreamedAt: Long?,
    armed: Boolean,
    now: Long,
    onArm: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Continuous readings", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            if (session == null) {
                Text(
                    "The sensor can broadcast a reading every minute over Bluetooth, which " +
                        "removes the tapping and the eight-hour limit on filling in history. " +
                        "Turning it on takes one NFC scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only one phone can stream from a sensor. Set this up on the phone that " +
                        "stays with the sensor; the other can still scan over NFC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (armed) {
                    Text(
                        "Hold the phone against the sensor now.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onCancel) { Text("Cancel") }
                } else {
                    var denied by remember { mutableStateOf(false) }

                    // Bluetooth and the ongoing notification are both runtime permissions on
                    // recent Android. Without them the service starts and immediately dies, so
                    // they are asked for before arming rather than after.
                    val needed = remember {
                        buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                add(Manifest.permission.BLUETOOTH_SCAN)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        // Notifications being refused is survivable; Bluetooth is not.
                        val bluetoothOk = result
                            .filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
                            .all { it.value }
                        if (bluetoothOk) {
                            denied = false
                            onArm()
                        } else {
                            denied = true
                        }
                    }

                    Button(onClick = {
                        if (needed.isEmpty()) onArm() else permissionLauncher.launch(needed.toTypedArray())
                    }) { Text("Turn on streaming") }

                    if (denied) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Streaming needs Bluetooth permission. You can grant it in " +
                                "Android's app settings and try again. Scanning over NFC is " +
                                "unaffected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                return@Column
            }

            val statusText = when (state) {
                is StreamState.Listening -> "Connected"
                is StreamState.Connecting -> "Waiting for the sensor to broadcast"
                is StreamState.Disconnected -> "Not connected"
                is StreamState.Failed -> state.reason
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)

            Text(
                lastStreamedAt?.let { "Last reading received ${formatElapsed(it, now)}" }
                    ?: "No reading received yet — the sensor broadcasts about once a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Sensor at ${session.macAddress}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Keep the ongoing notification: Android stops collecting if it is dismissed. " +
                    "Scanning over NFC keeps working regardless.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onStop) { Text("Turn off streaming") }
        }
    }
}


/**
 * The raw numbers behind the most recent reading.
 *
 * A glucose value can look entirely reasonable and still be wrong, and without a meter to check
 * it against there is no way to tell from the number alone. This exposes the inputs — the sensor's
 * factory calibration and the raw counts a reading was computed from — so the arithmetic can be
 * verified independently instead of trusted.
 *
 * Only populated after a scan in this session, since it is deliberately not stored.
 */
@Composable
private fun DiagnosticsCard(scan: SensorScan?, scanLog: List<ScanLogEntry>, now: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sensor diagnostics", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            if (scan == null) {
                Text(
                    "Scan the sensor to see the raw values behind a reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val cal = scan.calibration
            val lines = buildList {
                add("serial: ${scan.serial}")
                add("state: ${scan.state}  family: ${scan.family}")
                add("age: ${scan.ageMinutes} min   max life: ${scan.maxLifeMinutes} min")
                add("calibration i1..i6: ${cal.i1}, ${cal.i2}, ${cal.i3}, ${cal.i4}, ${cal.i5}, ${cal.i6}")
                add("")
                add("trend, newest first (raw / temp / adj -> mg/dL):")
                scan.trend.take(8).forEach {
                    add(
                        "  ${it.minutesSinceStart}: ${it.rawValue} / ${it.rawTemperature} / " +
                            "${it.temperatureAdjustment} -> ${it.mgdl?.toInt() ?: "-"}" +
                            if (it.hasError) "  ERR(${it.dataQuality})" else ""
                    )
                }
                add("")
                add("history, newest first:")
                scan.history.take(4).forEach {
                    add(
                        "  ${it.minutesSinceStart}: ${it.rawValue} / ${it.rawTemperature} / " +
                            "${it.temperatureAdjustment} -> ${it.mgdl?.toInt() ?: "-"}"
                    )
                }
            }

            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            )
            if (scanLog.size >= 2) {
                Spacer(Modifier.height(10.dp))
                Text("scan history:", style = MaterialTheme.typography.bodySmall)
                val log = scanLog.zipWithNext().map { (newer, older) ->
                    val wallMinutes = (newer.at - older.at) / 60_000L
                    val sensorMinutes = newer.sensorAgeMinutes - older.sensorAgeMinutes
                    "  +${wallMinutes}m elapsed -> sensor clock +${sensorMinutes}m, " +
                        "raw ${older.rawValue}->${newer.rawValue}, " +
                        "${older.mgdl?.toInt() ?: "-"}->${newer.mgdl?.toInt() ?: "-"} mg/dL"
                }
                Text(
                    log.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "If elapsed time advances but the sensor clock does not, the sensor has " +
                        "stopped rather than the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "If a reading looks wrong, a photo of this is enough to check the calculation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
