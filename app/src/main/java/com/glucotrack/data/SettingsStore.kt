package com.glucotrack.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "glucotrack_settings")

/** User preferences: display units and the glucose targets to measure against. */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    suspend fun setUnit(unit: GlucoseUnit) {
        context.dataStore.edit { it[UNIT] = unit.name }
    }

    suspend fun setTargets(targets: GlucoseTargets) {
        context.dataStore.edit {
            it[LOW] = targets.lowMgdl
            it[HIGH] = targets.highMgdl
            it[FASTING] = targets.fastingCeilingMgdl
            it[ONE_HOUR] = targets.oneHourCeilingMgdl
            it[TWO_HOUR] = targets.twoHourCeilingMgdl
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = GlucoseTargets.PREGNANCY
        return AppSettings(
            unit = runCatching { GlucoseUnit.valueOf(this[UNIT] ?: "") }
                .getOrDefault(GlucoseUnit.MGDL),
            targets = GlucoseTargets(
                lowMgdl = this[LOW] ?: defaults.lowMgdl,
                highMgdl = this[HIGH] ?: defaults.highMgdl,
                fastingCeilingMgdl = this[FASTING] ?: defaults.fastingCeilingMgdl,
                oneHourCeilingMgdl = this[ONE_HOUR] ?: defaults.oneHourCeilingMgdl,
                twoHourCeilingMgdl = this[TWO_HOUR] ?: defaults.twoHourCeilingMgdl,
            ),
        )
    }

    private companion object {
        val UNIT = stringPreferencesKey("unit")
        val LOW = doublePreferencesKey("target_low")
        val HIGH = doublePreferencesKey("target_high")
        val FASTING = doublePreferencesKey("target_fasting")
        val ONE_HOUR = doublePreferencesKey("target_one_hour")
        val TWO_HOUR = doublePreferencesKey("target_two_hour")
    }
}

data class AppSettings(
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val targets: GlucoseTargets = GlucoseTargets.PREGNANCY,
)
