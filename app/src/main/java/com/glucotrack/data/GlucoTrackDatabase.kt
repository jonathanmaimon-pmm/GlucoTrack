package com.glucotrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun mealTypeToString(value: MealType): String = value.name

    @TypeConverter
    fun stringToMealType(value: String): MealType =
        runCatching { MealType.valueOf(value) }.getOrDefault(MealType.OTHER)
}

@Database(
    entities = [GlucoseReading::class, NutritionEntry::class, SensorRecord::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GlucoTrackDatabase : RoomDatabase() {

    abstract fun glucoseDao(): GlucoseDao
    abstract fun nutritionDao(): NutritionDao
    abstract fun sensorDao(): SensorDao

    companion object {
        /**
         * Adds the factory calibration columns needed to decode BLE packets.
         *
         * Written as a migration rather than a destructive rebuild: by the time this ships the
         * database holds real readings, and the sensor's own memory only reaches back eight
         * hours, so anything dropped here would be gone for good.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("calI1", "calI2", "calI3", "calI4", "calI5", "calI6").forEach { column ->
                    db.execSQL("ALTER TABLE sensors ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        @Volatile
        private var instance: GlucoTrackDatabase? = null

        fun get(context: Context): GlucoTrackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GlucoTrackDatabase::class.java,
                    "glucotrack.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
