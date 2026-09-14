package com.glucotrack.analysis

import com.glucotrack.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class FastingAnalysisTest {

    /** Built through Calendar so the test holds in whatever timezone it runs in. */
    private fun at(daysAgo: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun reading(timestamp: Long, mgdl: Double) = GlucoseReading(
        sensorSerial = "3TEST",
        minutesSinceStart = (timestamp / 60_000L).toInt(),
        timestamp = timestamp,
        mgdl = mgdl,
        fromTrend = false,
    )

    private val now = at(0, 12)

    @Test
    fun `picks the reading nearest the target hour`() {
        val readings = listOf(
            reading(at(0, 3), 120.0),   // outside the window
            reading(at(0, 6, 45), 88.0), // nearest 07:00
            reading(at(0, 8, 30), 140.0),
        )
        val fasting = FastingAnalysis.dailyFasting(readings, days = 1, now = now)
        assertEquals(1, fasting.size)
        assertEquals(88.0, fasting[0].mgdl!!, 0.001)
    }

    @Test
    fun `days without coverage are reported as null not omitted`() {
        val readings = listOf(reading(at(0, 7), 90.0))
        val fasting = FastingAnalysis.dailyFasting(readings, days = 3, now = now)
        assertEquals(3, fasting.size)
        assertEquals(90.0, fasting[0].mgdl!!, 0.001)
        assertNull(fasting[1].mgdl)
        assertNull(fasting[2].mgdl)
    }

    @Test
    fun `readings far from the target hour do not count`() {
        // Midday only — nothing within two hours of 07:00.
        val readings = listOf(reading(at(0, 13), 100.0))
        assertNull(FastingAnalysis.dailyFasting(readings, days = 1, now = now)[0].mgdl)
    }

    @Test
    fun `fasting values are checked against the fasting ceiling not the general range`() {
        val targets = GlucoseTargets.PREGNANCY
        val over = FastingAnalysis.dailyFasting(
            listOf(reading(at(0, 7), 110.0)), days = 1, now = now,
        )[0]
        // 110 sits inside the 63-140 day range but above the 95 fasting ceiling.
        assertEquals(GlucoseBand.IN_RANGE, targets.classify(110.0))
        assertEquals(true, over.exceeds(targets))
    }

    @Test
    fun `each day is matched independently`() {
        val readings = listOf(
            reading(at(0, 7), 88.0),
            reading(at(1, 7), 101.0),
        )
        val fasting = FastingAnalysis.dailyFasting(readings, days = 2, now = now)
        assertEquals(88.0, fasting[0].mgdl!!, 0.001)
        assertEquals(101.0, fasting[1].mgdl!!, 0.001)
    }
}
