package com.glucotrack.analysis

import com.glucotrack.data.GlucoseReading
import com.glucotrack.data.MealType
import com.glucotrack.data.NutritionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealImpactTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun reading(minutesFromMeal: Long, mgdl: Double) = GlucoseReading(
        sensorSerial = "3TEST",
        minutesSinceStart = minutesFromMeal.toInt() + 1000,
        timestamp = t0 + minutesFromMeal * minute,
        mgdl = mgdl,
        fromTrend = false,
    )

    private val meal = NutritionEntry(
        id = 1,
        timestamp = t0,
        description = "Porridge and orange juice",
        carbsGrams = 60,
        mealType = MealType.BREAKFAST,
    )

    /** A meal followed by a rise to 165 at one hour and a partial fall by two. */
    private val curve = listOf(
        reading(-10, 92.0),
        reading(30, 140.0),
        reading(60, 165.0),
        reading(90, 150.0),
        reading(120, 128.0),
        reading(150, 105.0),
    )

    @Test
    fun `baseline peak and post-meal readings are matched`() {
        val impact = MealAnalysis.analyse(listOf(meal), curve).single()
        assertEquals(92.0, impact.baselineMgdl!!, 0.001)
        assertEquals(165.0, impact.oneHourMgdl!!, 0.001)
        assertEquals(128.0, impact.twoHourMgdl!!, 0.001)
        assertEquals(165.0, impact.peakMgdl!!, 0.001)
        assertEquals(73.0, impact.riseMgdl!!, 0.001)
    }

    @Test
    fun `post-meal readings are checked against the pregnancy ceilings`() {
        val impact = MealAnalysis.analyse(listOf(meal), curve).single()
        val targets = GlucoseTargets.PREGNANCY
        // 165 at one hour is over the 140 ceiling; 128 at two hours is over the 120 ceiling.
        assertTrue(impact.exceededOneHourTarget(targets)!!)
        assertTrue(impact.exceededTwoHourTarget(targets)!!)
    }

    @Test
    fun `a meal that stays within target is not flagged`() {
        val gentle = listOf(
            reading(-5, 88.0), reading(60, 118.0), reading(120, 99.0)
        )
        val impact = MealAnalysis.analyse(listOf(meal), gentle).single()
        val targets = GlucoseTargets.PREGNANCY
        assertFalse(impact.exceededOneHourTarget(targets)!!)
        assertFalse(impact.exceededTwoHourTarget(targets)!!)
    }

    @Test
    fun `readings too far from the mark are reported as unknown not guessed`() {
        // Nothing within tolerance of the one- and two-hour marks.
        val sparse = listOf(reading(-5, 90.0), reading(200, 110.0))
        val impact = MealAnalysis.analyse(listOf(meal), sparse).single()
        assertEquals(90.0, impact.baselineMgdl!!, 0.001)
        assertNull(impact.oneHourMgdl)
        assertNull(impact.twoHourMgdl)
    }

    @Test
    fun `a meal with no scans nearby reports no coverage`() {
        val impact = MealAnalysis.analyse(listOf(meal), emptyList()).single()
        assertTrue(impact.hasNoCoverage)
        assertNull(impact.riseMgdl)
    }

    @Test
    fun `readings before the meal do not count towards the peak`() {
        val spikeBefore = listOf(reading(-20, 190.0), reading(-5, 95.0), reading(60, 120.0))
        val impact = MealAnalysis.analyse(listOf(meal), spikeBefore).single()
        assertEquals(120.0, impact.peakMgdl!!, 0.001)
    }

    @Test
    fun `ranking puts the biggest rise first and drops uncovered meals`() {
        val second = meal.copy(id = 2, timestamp = t0 + 6 * 60 * minute, description = "Salad")
        val readings = curve + listOf(
            reading(355, 95.0),
            reading(420, 105.0),
        )
        val ranked = MealAnalysis.rankByRise(MealAnalysis.analyse(listOf(meal, second), readings))
        assertEquals(2, ranked.size)
        assertEquals("Porridge and orange juice", ranked[0].entry.description)
        assertEquals("Salad", ranked[1].entry.description)
        assertTrue(ranked[0].riseMgdl!! > ranked[1].riseMgdl!!)
    }
}
