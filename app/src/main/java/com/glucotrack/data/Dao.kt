package com.glucotrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<GlucoseReading>)

    /** Readings in a time window, oldest first — the order charts and statistics expect. */
    @Query(
        "SELECT * FROM glucose_readings WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC"
    )
    fun readingsBetween(from: Long, to: Long): Flow<List<GlucoseReading>>

    @Query(
        "SELECT * FROM glucose_readings WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC"
    )
    suspend fun readingsBetweenOnce(from: Long, to: Long): List<GlucoseReading>

    @Query("SELECT * FROM glucose_readings ORDER BY timestamp DESC LIMIT 1")
    fun latestReading(): Flow<GlucoseReading?>

    @Query("SELECT COUNT(*) FROM glucose_readings")
    fun readingCount(): Flow<Int>

    @Query("DELETE FROM glucose_readings")
    suspend fun deleteAllReadings()
}

@Dao
interface NutritionDao {

    @Upsert
    suspend fun upsert(entry: NutritionEntry): Long

    @Query("DELETE FROM nutrition_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM nutrition_entries ORDER BY timestamp DESC")
    fun allEntries(): Flow<List<NutritionEntry>>

    @Query(
        "SELECT * FROM nutrition_entries WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC"
    )
    fun entriesBetween(from: Long, to: Long): Flow<List<NutritionEntry>>

    @Query("SELECT * FROM nutrition_entries ORDER BY timestamp DESC")
    suspend fun allEntriesOnce(): List<NutritionEntry>

    @Query("DELETE FROM nutrition_entries")
    suspend fun deleteAll()
}

@Dao
interface SensorDao {

    @Upsert
    suspend fun upsert(sensor: SensorRecord)

    @Query("SELECT * FROM sensors ORDER BY lastScanAt DESC LIMIT 1")
    fun mostRecent(): Flow<SensorRecord?>

    @Query("SELECT * FROM sensors ORDER BY lastScanAt DESC")
    suspend fun allOnce(): List<SensorRecord>

    @Query("DELETE FROM sensors")
    suspend fun deleteAll()
}
