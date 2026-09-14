package com.glucotrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GlucoTrackDatabase : RoomDatabase() {

    abstract fun glucoseDao(): GlucoseDao
    abstract fun nutritionDao(): NutritionDao
    abstract fun sensorDao(): SensorDao

    companion object {
        @Volatile
        private var instance: GlucoTrackDatabase? = null

        fun get(context: Context): GlucoTrackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GlucoTrackDatabase::class.java,
                    "glucotrack.db",
                ).build().also { instance = it }
            }
    }
}
