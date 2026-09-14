package com.glucotrack.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export exists so two phones can be reconciled without a server, which means the file has
 * to survive a round trip through a share sheet, a messaging app, and a file picker with the
 * readings intact.
 */
class TransferTest {

    private val t0 = 1_700_000_000_000L

    private fun reading(minute: Int, mgdl: Double, serial: String = "3MH01P8EE40") =
        GlucoseReading(serial, minute, t0 + minute * 60_000L, mgdl, fromTrend = false)

    private val snapshot = Transfer.Snapshot(
        readings = listOf(reading(100, 96.0), reading(115, 142.5), reading(130, 88.0)),
        meals = listOf(
            NutritionEntry(1, t0, "Porridge", 45, MealType.BREAKFAST, "with honey"),
            NutritionEntry(2, t0 + 3_600_000, "Salad", null, MealType.LUNCH, null),
        ),
        sensors = listOf(SensorRecord("3MH01P8EE40", t0 - 86_400_000, 20880, t0, "ACTIVE")),
    )

    @Test
    fun `a snapshot survives a round trip unchanged`() {
        val parsed = Transfer.parse(Transfer.export(snapshot, t0))

        assertEquals(3, parsed.readings.size)
        assertEquals(96.0, parsed.readings[0].mgdl, 0.0001)
        assertEquals(115, parsed.readings[1].minutesSinceStart)
        assertEquals("3MH01P8EE40", parsed.readings[0].sensorSerial)

        assertEquals(2, parsed.meals.size)
        assertEquals("Porridge", parsed.meals[0].description)
        assertEquals(45, parsed.meals[0].carbsGrams)
        assertEquals(MealType.BREAKFAST, parsed.meals[0].mealType)
        assertEquals("with honey", parsed.meals[0].notes)

        assertEquals(1, parsed.sensors.size)
        assertEquals(20880, parsed.sensors[0].maxLifeMinutes)
    }

    @Test
    fun `a meal description containing commas and quotes survives`() {
        // Free text is user-entered, so it will eventually contain the delimiter.
        val awkward = """Rice, beans and "the good" sauce"""
        val s = Transfer.Snapshot(
            meals = listOf(NutritionEntry(1, t0, awkward, 70, MealType.DINNER, "ate it, all of it"))
        )
        val parsed = Transfer.parse(Transfer.export(s, t0))
        assertEquals(awkward, parsed.meals[0].description)
        assertEquals("ate it, all of it", parsed.meals[0].notes)
    }

    @Test
    fun `absent optional fields stay absent rather than becoming empty text`() {
        val parsed = Transfer.parse(Transfer.export(snapshot, t0))
        val salad = parsed.meals.first { it.description == "Salad" }
        assertEquals(null, salad.carbsGrams)
        assertEquals(null, salad.notes)
    }

    @Test
    fun `a file from something else is rejected`() {
        val e = runCatching { Transfer.parse("date,value\n2026-01-01,100\n") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `a mangled row is skipped without losing the rest of the file`() {
        // A partially readable file from the other phone beats an error message.
        val text = Transfer.export(snapshot, t0)
            .replace("${t0 + 115 * 60_000L},142.5", "NOT_A_NUMBER,142.5")
        val parsed = Transfer.parse(text)
        assertEquals(2, parsed.readings.size)
        assertTrue(parsed.readings.none { it.minutesSinceStart == 115 })
        assertEquals(2, parsed.meals.size)
    }

    @Test
    fun `meal identity ignores case and surrounding space`() {
        // The same meal typed on two phones should merge, not duplicate.
        val a = NutritionEntry(1, t0, "Porridge", 45, MealType.BREAKFAST)
        val b = NutritionEntry(99, t0, "  porridge ", 45, MealType.BREAKFAST)
        assertEquals(Transfer.mealKey(a), Transfer.mealKey(b))
    }

    @Test
    fun `meals at different times are distinct`() {
        val a = NutritionEntry(1, t0, "Porridge")
        val b = NutritionEntry(2, t0 + 60_000, "Porridge")
        assertTrue(Transfer.mealKey(a) != Transfer.mealKey(b))
    }

    @Test
    fun `an empty phone exports a file that parses to nothing`() {
        val parsed = Transfer.parse(Transfer.export(Transfer.Snapshot(), t0))
        assertEquals(0, parsed.readings.size)
        assertEquals(0, parsed.meals.size)
    }

    @Test
    fun `the export is human readable and states its format`() {
        val text = Transfer.export(snapshot, t0)
        assertTrue(text.startsWith("# GlucoTrack export v${Transfer.FORMAT_VERSION}"))
        assertTrue(text.contains("[readings]"))
        assertTrue(text.contains("[meals]"))
    }
}
