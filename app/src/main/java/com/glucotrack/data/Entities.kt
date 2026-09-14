package com.glucotrack.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One glucose measurement.
 *
 * The primary key is the sensor serial plus the sensor's own internal clock, which makes storing
 * a scan idempotent: each tap returns overlapping trend and history windows, and re-inserting a
 * measurement already on file simply overwrites it with an identical row rather than creating a
 * duplicate. That is what lets repeated scans accumulate into one continuous record.
 */
@Entity(
    tableName = "glucose_readings",
    primaryKeys = ["sensorSerial", "minutesSinceStart"],
    indices = [Index("timestamp")],
)
data class GlucoseReading(
    val sensorSerial: String,
    /** Minutes since this sensor was activated — stable across scans, unlike wall-clock time. */
    val minutesSinceStart: Int,
    val timestamp: Long,
    val mgdl: Double,
    /** True when taken from the 1-minute trend ring rather than the 15-minute history ring. */
    val fromTrend: Boolean,
)

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK, DRINK, OTHER }

/**
 * Something eaten or drunk, logged so it can be lined up against what glucose did afterwards.
 *
 * Carbohydrates are optional: a rough note recorded at the time is worth more than an accurate
 * one that never gets entered.
 */
@Entity(tableName = "nutrition_entries", indices = [Index("timestamp")])
data class NutritionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val description: String,
    val carbsGrams: Int? = null,
    val mealType: MealType = MealType.OTHER,
    val notes: String? = null,
)

/** A sensor this phone has scanned, so the app can show its age without a fresh tap. */
@Entity(tableName = "sensors")
data class SensorRecord(
    @PrimaryKey val serial: String,
    val activatedAt: Long,
    val maxLifeMinutes: Int,
    val lastScanAt: Long,
    val lastState: String,
)
