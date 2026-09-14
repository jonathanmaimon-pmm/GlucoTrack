package com.glucotrack.analysis

import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.NutritionEntry
import kotlin.math.abs

/**
 * What glucose did after a logged meal.
 *
 * Fields are null when the sensor was not scanned closely enough around that moment to know —
 * an absent value is reported as absent rather than filled in from a distant reading, because
 * "we don't know" and "it was fine" need to stay distinguishable.
 */
data class MealImpact(
    val entry: NutritionEntry,
    /** Glucose just before eating. */
    val baselineMgdl: Double?,
    val oneHourMgdl: Double?,
    val twoHourMgdl: Double?,
    /** Highest reading in the window after the meal. */
    val peakMgdl: Double?,
    val peakAtMillis: Long?,
) {
    /** How far glucose climbed from baseline to peak. */
    val riseMgdl: Double?
        get() {
            val b = baselineMgdl ?: return null
            val p = peakMgdl ?: return null
            return p - b
        }

    fun exceededOneHourTarget(targets: GlucoseTargets): Boolean? =
        oneHourMgdl?.let { it > targets.oneHourCeilingMgdl }

    fun exceededTwoHourTarget(targets: GlucoseTargets): Boolean? =
        twoHourMgdl?.let { it > targets.twoHourCeilingMgdl }

    /** True when nothing useful was captured around this meal. */
    val hasNoCoverage: Boolean
        get() = baselineMgdl == null && oneHourMgdl == null &&
            twoHourMgdl == null && peakMgdl == null
}

object MealAnalysis {

    private const val MINUTE = 60_000L

    /** How far from the target moment a reading may sit and still be taken to represent it. */
    private const val MATCH_TOLERANCE = 12 * MINUTE

    /** A reading just before the meal counts as the baseline. */
    private val BASELINE_WINDOW = -25 * MINUTE..5 * MINUTE

    /** Post-meal excursions are tracked for three hours. */
    private const val PEAK_WINDOW = 3 * 60 * MINUTE

    /**
     * Lines each entry up against the glucose record.
     *
     * [readings] need not be sorted or complete; anything the scans missed simply comes back null.
     */
    fun analyse(entries: List<NutritionEntry>, readings: List<GlucoseReading>): List<MealImpact> {
        if (entries.isEmpty()) return emptyList()
        val sorted = readings.sortedBy { it.timestamp }
        return entries.map { entry -> analyseOne(entry, sorted) }
    }

    private fun analyseOne(entry: NutritionEntry, sorted: List<GlucoseReading>): MealImpact {
        val t = entry.timestamp

        val baseline = sorted
            .filter { it.timestamp - t in BASELINE_WINDOW }
            .minByOrNull { abs(it.timestamp - t) }

        val after = sorted.filter { it.timestamp in (t + 1)..(t + PEAK_WINDOW) }
        val peak = after.maxByOrNull { it.mgdl }

        return MealImpact(
            entry = entry,
            baselineMgdl = baseline?.mgdl,
            oneHourMgdl = nearest(sorted, t + 60 * MINUTE)?.mgdl,
            twoHourMgdl = nearest(sorted, t + 120 * MINUTE)?.mgdl,
            peakMgdl = peak?.mgdl,
            peakAtMillis = peak?.timestamp,
        )
    }

    /** The reading closest to [target], or null if the nearest one is too far away to speak for it. */
    private fun nearest(sorted: List<GlucoseReading>, target: Long): GlucoseReading? =
        sorted.filter { abs(it.timestamp - target) <= MATCH_TOLERANCE }
            .minByOrNull { abs(it.timestamp - target) }

    /**
     * Meals ranked by how far glucose rose afterwards, highest first.
     *
     * Meals with no glucose coverage are dropped rather than ranked as zero-impact.
     */
    fun rankByRise(impacts: List<MealImpact>): List<MealImpact> =
        impacts.filter { it.riseMgdl != null }.sortedByDescending { it.riseMgdl }
}
