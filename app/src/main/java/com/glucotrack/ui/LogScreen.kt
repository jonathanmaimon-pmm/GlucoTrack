package com.glucotrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.analysis.MealAnalysis
import com.glucotrack.analysis.MealImpact
import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.MealType
import com.glucotrack.data.NutritionEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    meals: List<NutritionEntry>,
    readings: List<GlucoseReading>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    onAdd: (Long, String, Int?, MealType, String?) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val impacts = remember(meals, readings) { MealAnalysis.analyse(meals, readings) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton({ showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Log food")
            }
        },
    ) { padding ->
        if (meals.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Log what you eat and the app will line it up against what glucose did " +
                        "over the following two hours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(impacts, key = { it.entry.id }) { impact ->
                    MealCard(impact, targets, unit, onDelete)
                }
            }
        }
    }

    if (showDialog) {
        AddMealDialog(
            onDismiss = { showDialog = false },
            onConfirm = { time, desc, carbs, type, notes ->
                onAdd(time, desc, carbs, type, notes)
                showDialog = false
            },
        )
    }
}

@Composable
private fun MealCard(
    impact: MealImpact,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    onDelete: (Long) -> Unit,
) {
    val entry = impact.entry
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(entry.description, style = MaterialTheme.typography.titleSmall)
                    Text(
                        formatDayTime(entry.timestamp) +
                            (entry.carbsGrams?.let { " · ${it}g carbs" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton({ onDelete(entry.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete entry")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (impact.hasNoCoverage) {
                Text(
                    "No sensor readings around this time — scan within a couple of hours of " +
                        "eating to capture the response.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingChip("Before", impact.baselineMgdl, unit, false)
                    ReadingChip(
                        "1 h", impact.oneHourMgdl, unit,
                        impact.exceededOneHourTarget(targets) == true,
                    )
                    ReadingChip(
                        "2 h", impact.twoHourMgdl, unit,
                        impact.exceededTwoHourTarget(targets) == true,
                    )
                }
                impact.riseMgdl?.let { rise ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Peak ${unit.format(impact.peakMgdl!!)} ${unit.label}, " +
                            "up ${unit.format(rise)} from before",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            entry.notes?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReadingChip(label: String, mgdl: Double?, unit: GlucoseUnit, overTarget: Boolean) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                if (mgdl == null) "$label —" else "$label ${unit.format(mgdl)}",
                color = if (overTarget) BandColors.high else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMealDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long, String, Int?, MealType, String?) -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var mealType by remember { mutableStateOf(MealType.OTHER) }
    // Defaults to now; the offset buttons cover the common case of logging a meal after the fact.
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log food or drink") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What was it?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = carbs,
                    onValueChange = { carbs = it.filter(Char::isDigit).take(4) },
                    label = { Text("Carbs in grams (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MealType.entries.take(4).forEach { type ->
                        FilterChip(
                            selected = mealType == type,
                            onClick = { mealType = type },
                            label = { Text(type.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ timestamp -= 15 * 60_000L }) { Text("−15 min") }
                    Text(
                        formatTime(timestamp),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    TextButton(
                        onClick = { timestamp += 15 * 60_000L },
                        enabled = timestamp + 15 * 60_000L <= System.currentTimeMillis(),
                    ) { Text("+15 min") }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        timestamp,
                        description,
                        carbs.toIntOrNull(),
                        mealType,
                        notes.ifBlank { null },
                    )
                },
                enabled = description.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
