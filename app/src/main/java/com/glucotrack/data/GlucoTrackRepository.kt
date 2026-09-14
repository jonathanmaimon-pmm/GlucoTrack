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

    suspend fun currentSensorOnce(): SensorRecord? = db.sensorDao().allOnce().firstOrNull()

    /** Stores readings recovered over BLE, keyed the same way as scanned ones. */
    suspend fun saveBleReading(serial: String, reading: com.glucotrack.sensor.BleReading) {
        val rows = (reading.history + reading.trend)
            .filter { it.isValid }
            .map {
                GlucoseReading(
                    sensorSerial = serial,
                    minutesSinceStart = it.minutesSinceStart,
                    timestamp = it.timestamp,
                    mgdl = it.mgdl!!,
                    fromTrend = true,
                )
            }
            .associateBy { it.minutesSinceStart }
            .values.toList()
        if (rows.isNotEmpty()) db.glucoseDao().insertAll(rows)
    }

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
                calI1 = scan.calibration.i1,
                calI2 = scan.calibration.i2,
                calI3 = scan.calibration.i3,
                calI4 = scan.calibration.i4,
                calI5 = scan.calibration.i5,
                calI6 = scan.calibration.i6,
            )
        )
    }

    /** Everything this phone holds, for handing to the other phone. */
    suspend fun snapshot(): Transfer.Snapshot = Transfer.Snapshot(
        readings = db.glucoseDao().readingsBetweenOnce(0L, Long.MAX_VALUE),
        meals = db.nutritionDao().allEntriesOnce(),
        sensors = db.sensorDao().allOnce(),
    )

    /**
     * Merges an export from another phone into this one.
     *
     * Additive only: nothing already here is removed, and re-importing the same file changes
     * nothing. Readings collapse on their natural key, so a measurement both phones captured is
     * stored once. Meals are matched on when they were eaten and what they were, because row ids
     * are assigned per device and cannot identify the same meal across two installations.
     */
    suspend fun merge(snapshot: Transfer.Snapshot): Transfer.MergeSummary {
        val existingReadings = db.glucoseDao().readingsBetweenOnce(0L, Long.MAX_VALUE)
            .map { it.sensorSerial to it.minutesSinceStart }
            .toHashSet()
        val newReadings = snapshot.readings
            .filter { (it.sensorSerial to it.minutesSinceStart) !in existingReadings }
        if (newReadings.isNotEmpty()) db.glucoseDao().insertAll(newReadings)

        val existingMeals = db.nutritionDao().allEntriesOnce()
            .map { Transfer.mealKey(it) }
            .toHashSet()
        val newMeals = snapshot.meals.filter { Transfer.mealKey(it) !in existingMeals }
        newMeals.forEach { db.nutritionDao().upsert(it.copy(id = 0)) }

        val existingSensors = db.sensorDao().allOnce().associateBy { it.serial }
        var sensorsAdded = 0
        snapshot.sensors.forEach { incoming ->
            val current = existingSensors[incoming.serial]
            if (current == null) {
                db.sensorDao().upsert(incoming)
                sensorsAdded++
            } else if (incoming.lastScanAt > current.lastScanAt) {
                // Keep whichever phone scanned it most recently.
                db.sensorDao().upsert(incoming)
            }
        }

        return Transfer.MergeSummary(
            readingsAdded = newReadings.size,
            readingsAlreadyPresent = snapshot.readings.size - newReadings.size,
            mealsAdded = newMeals.size,
            mealsAlreadyPresent = snapshot.meals.size - newMeals.size,
            sensorsAdded = sensorsAdded,
        )
    }

    /** Wipes everything on the device. Used by the "delete all data" action in Settings. */
    suspend fun deleteEverything() {
        db.glucoseDao().deleteAllReadings()
        db.nutritionDao().deleteAll()
        db.sensorDao().deleteAll()
    }
}
