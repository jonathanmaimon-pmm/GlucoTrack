package com.glucotrack.data

import com.glucotrack.sensor.SensorScan
import kotlinx.coroutines.flow.Flow

/** Single point of access to stored readings, meals and sensor records. */
class GlucoTrackRepository(private val db: GlucoTrackDatabase) {

    fun latestReading(): Flow<GlucoseReading?> = db.glucoseDao().latestReading()

    fun readingsBetween(from: Long, to: Long): Flow<List<GlucoseReading>> =
        db.glucoseDao().readingsBetween(from, to)

    suspend fun readingsBetweenOnce(from: Long, to: Long): List<GlucoseReading> =
        db.glucoseDao().readingsBetweenOnce(from, to)

    fun readingCount(): Flow<Int> = db.glucoseDao().readingCount()

    fun currentSensor(): Flow<SensorRecord?> = db.sensorDao().mostRecent()

    fun nutritionEntries(): Flow<List<NutritionEntry>> = db.nutritionDao().allEntries()

    suspend fun saveNutritionEntry(entry: NutritionEntry) {
        db.nutritionDao().upsert(entry)
    }

    suspend fun deleteNutritionEntry(id: Long) {
        db.nutritionDao().delete(id)
    }

    /**
     * Persists one scan.
     *
     * Both rings are stored. Where a measurement appears in each, the trend copy wins: it comes
     * from the sensor's higher-resolution buffer. Rows are keyed by sensor and sensor-clock
     * minute, so re-scanning overwrites rather than duplicates.
     */
    suspend fun saveScan(scan: SensorScan) {
        val rows = buildList {
            scan.history.filter { it.isValid }.forEach {
                add(
                    GlucoseReading(
                        sensorSerial = scan.serial,
                        minutesSinceStart = it.minutesSinceStart,
                        timestamp = it.timestamp,
                        mgdl = it.mgdl!!,
                        fromTrend = false,
                    )
                )
            }
            scan.trend.filter { it.isValid }.forEach {
                add(
                    GlucoseReading(
                        sensorSerial = scan.serial,
                        minutesSinceStart = it.minutesSinceStart,
                        timestamp = it.timestamp,
                        mgdl = it.mgdl!!,
                        fromTrend = true,
                    )
                )
            }
        }.associateBy { it.minutesSinceStart }.values.toList()

        if (rows.isNotEmpty()) db.glucoseDao().insertAll(rows)

        db.sensorDao().upsert(
            SensorRecord(
                serial = scan.serial,
                activatedAt = scan.scannedAt - scan.ageMinutes * 60_000L,
                maxLifeMinutes = scan.maxLifeMinutes,
                lastScanAt = scan.scannedAt,
                lastState = scan.state.name,
            )
        )
    }

    /** Wipes everything on the device. Used by the "delete all data" action in Settings. */
    suspend fun deleteEverything() {
        db.glucoseDao().deleteAllReadings()
        db.nutritionDao().deleteAll()
        db.sensorDao().deleteAll()
    }
}
