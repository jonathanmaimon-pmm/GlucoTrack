package com.glucotrack.analysis

/**
 * Glucose thresholds the app measures against, in mg/dL.
 *
 * The defaults are the pregnancy targets, which are tighter than the general population ones —
 * using standard diabetes thresholds during pregnancy would make readings look fine when they are
 * not. They are surfaced and editable in Settings rather than buried here, because the numbers a
 * clinician gives for a specific pregnancy take precedence over any default shipped in an app.
 *
 * Sources for the defaults: ADA Standards of Care and ACOG guidance for gestational diabetes
 * (fasting < 95, 1-hour post-meal < 140, 2-hour post-meal < 120), and the international consensus
 * CGM range for pregnancy (63–140).
 */
data class GlucoseTargets(
    val lowMgdl: Double = 63.0,
    val highMgdl: Double = 140.0,
    val fastingCeilingMgdl: Double = 95.0,
    val oneHourCeilingMgdl: Double = 140.0,
    val twoHourCeilingMgdl: Double = 120.0,
) {
    fun classify(mgdl: Double): GlucoseBand = when {
        mgdl < lowMgdl -> GlucoseBand.LOW
        mgdl > highMgdl -> GlucoseBand.HIGH
        else -> GlucoseBand.IN_RANGE
    }

    companion object {
        /** The shipped defaults, labelled in the UI as pregnancy targets. */
        val PREGNANCY = GlucoseTargets()
    }
}

enum class GlucoseBand { LOW, IN_RANGE, HIGH }

/** Display units. Israel and most of Europe report mg/dL; the UK and Canada use mmol/L. */
enum class GlucoseUnit(val label: String) {
    MGDL("mg/dL"),
    MMOL("mmol/L");

    fun from(mgdl: Double): Double = if (this == MGDL) mgdl else mgdl / 18.0182

    /** Formats a value for display, with the precision each unit conventionally carries. */
    fun format(mgdl: Double): String = when (this) {
        MGDL -> mgdl.toInt().toString()
        MMOL -> String.format("%.1f", from(mgdl))
    }
}
