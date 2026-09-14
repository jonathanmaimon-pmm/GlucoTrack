package com.glucotrack.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glucotrack.MainViewModel

private enum class Destination(val label: String, val icon: ImageVector) {
    NOW("Now", Icons.Default.ShowChart),
    HISTORY("History", Icons.Default.Timeline),
    LOG("Food", Icons.Default.Restaurant),
    REPORTS("Reports", Icons.Default.Assessment),
    SETTINGS("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoTrackApp(viewModel: MainViewModel) {
    var destination by remember { mutableStateOf(Destination.NOW) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val recent by viewModel.recentReadings.collectAsStateWithLifecycle()
    val all by viewModel.allReadings.collectAsStateWithLifecycle()
    val meals by viewModel.meals.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val armed by viewModel.armedToActivate.collectAsStateWithLifecycle()
    val now by viewModel.clock.collectAsStateWithLifecycle()
    val transferStatus by viewModel.transferStatus.collectAsStateWithLifecycle()
    val streamingSession by viewModel.streamingSession.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val lastStreamedAt by viewModel.lastStreamedAt.collectAsStateWithLifecycle()
    val armedStreaming by viewModel.armedToEnableStreaming.collectAsStateWithLifecycle()
    val lastScan by viewModel.lastScan.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("GlucoTrack") }) },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (destination) {
            Destination.NOW -> NowScreen(
                readings = recent,
                sensor = sensor,
                scanStatus = scanStatus,
                armedToActivate = armed,
                targets = settings.targets,
                unit = settings.unit,
                now = now,
                onArmActivation = viewModel::armActivation,
                onCancelActivation = viewModel::cancelActivation,
                onDismissStatus = viewModel::dismissScanStatus,
                modifier = content,
            )

            Destination.HISTORY -> HistoryScreen(
                readings = all,
                meals = meals,
                targets = settings.targets,
                unit = settings.unit,
                now = now,
                modifier = content,
            )

            Destination.LOG -> LogScreen(
                meals = meals,
                readings = all,
                targets = settings.targets,
                unit = settings.unit,
                onAdd = viewModel::addMeal,
                onDelete = viewModel::deleteMeal,
                modifier = content,
            )

            Destination.REPORTS -> ReportsScreen(
                readings = all,
                meals = meals,
                targets = settings.targets,
                unit = settings.unit,
                now = now,
                modifier = content,
            )

            Destination.SETTINGS -> SettingsScreen(
                unit = settings.unit,
                targets = settings.targets,
                readingCount = all.size,
                transferStatus = transferStatus,
                streamingSession = streamingSession,
                streamState = streamState,
                lastStreamedAt = lastStreamedAt,
                armedToEnableStreaming = armedStreaming,
                lastScan = lastScan,
                now = now,
                onUnitChange = viewModel::setUnit,
                onTargetsChange = viewModel::setTargets,
                onArmStreaming = viewModel::armStreamingEnable,
                onCancelStreaming = viewModel::cancelStreamingEnable,
                onStopStreaming = viewModel::stopStreaming,
                onExport = viewModel::export,
                onImport = viewModel::import,
                onDismissTransfer = viewModel::dismissTransferStatus,
                onDeleteEverything = viewModel::deleteEverything,
                modifier = content,
            )
        }
    }
}
