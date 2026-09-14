package com.glucotrack.analysis

import com.glucotrack.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun reading(minutesFromStart: Long, mgdl: Double) = GlucoseReading(
        sensorSerial = "3TEST",
        minutesSinceStart = minutesFromStart.toInt(),
        timestamp = t0 + minutesFromStart * minute,
        mgdl = mgdl,
        fromTrend = false,
    )

    @Test
    fun `empty input reports no data rather than zeros`() {
        val stats = GlucoseStatistics.summarise(emptyList())
        assertFalse(stats.hasData)
        assertNull(stats.averageMgdl)
        assertNull(stats.standardDeviation)
    }

    @Test
    fun `average min and max are reported`() {
        val stats = GlucoseStatistics.summarise(
            listOf(reading(0, 100.0), reading(15, 120.0), reading(30, 80.0))
        )
        assertEquals(100.0, stats.averageMgdl!!, 0.001)
        assertEquals(80.0, stats.minMgdl!!, 0.001)
        assertEquals(120.0, stats.maxMgdl!!, 0.001)
        assertEquals(3, stats.sampleCount)
    }

    @Test
    fun `evenly spaced readings split time in range proportionally`() {
        // Four readings a quarter-hour apart: three in range, one above.
        val stats = GlucoseStatistics.summarise(
            listOf(reading(0, 100.0), reading(15, 110.0), reading(30, 120.0), reading(45, 200.0))
        )
        assertEquals(75.0, stats.timeInRangePercent, 1.0)
        assertEquals(25.0, stats.timeAbovePercent, 1.0)
        assertEquals(0.0, stats.timeBelowPercent, 0.001)
    }

    @Test
    fun `a dense cluster does not outweigh the hours around it`() {
        // Sixteen 1-minute samples above range, against four hours of in-range history.
        // By sample count the high cluster would be 50% of the data; by time it is about 6%.
        val cluster = (0..15).map { reading(240L + it, 200.0) }
        val history = (0..15).map { reading(it * 15L, 100.0) }
        val stats = GlucoseStatistics.summarise(history + cluster)

        assertEquals(32, stats.sampleCount)
        assertTrue(
            "high share should be small by time, was ${stats.timeAbovePercent}",
            stats.timeAbovePercent < 15.0,
        )
        assertTrue(stats.timeInRangePercent > 85.0)
    }

    @Test
    fun `a gap in scanning is not counted as time at the last reading`() {
        // Two readings eight hours apart cannot mean eight hours at 200 mg/dL.
        val stats = GlucoseStatistics.summarise(
            listOf(reading(0, 100.0), reading(480, 200.0))
        )
        // Each stands for at most the 20-minute cap, so coverage is well under eight hours.
        assertTrue(
            "coverage should be capped, was ${stats.coveredMillis}",
            stats.coveredMillis < 60 * minute,
        )
    }

    @Test
    fun `readings below range are counted as low`() {
        val stats = GlucoseStatistics.summarise(
            listOf(reading(0, 50.0), reading(15, 100.0))
        )
        assertEquals(50.0, stats.timeBelowPercent, 1.0)
    }

    @Test
    fun `coefficient of variation is derived from average and spread`() {
        val stats = GlucoseStatistics.summarise(
            listOf(reading(0, 90.0), reading(15, 110.0), reading(30, 100.0))
        )
        assertEquals(10.0, stats.standardDeviation!!, 0.001)
        assertEquals(10.0, stats.coefficientOfVariation!!, 0.1)
    }

    @Test
    fun `pregnancy targets are tighter than general diabetes thresholds`() {
        val targets = GlucoseTargets.PREGNANCY
        assertEquals(63.0, targets.lowMgdl, 0.001)
        assertEquals(140.0, targets.highMgdl, 0.001)
        assertEquals(GlucoseBand.HIGH, targets.classify(150.0))
        assertEquals(GlucoseBand.IN_RANGE, targets.classify(120.0))
        assertEquals(GlucoseBand.LOW, targets.classify(60.0))
    }

    @Test
    fun `unit conversion and formatting match convention`() {
        assertEquals("100", GlucoseUnit.MGDL.format(100.0))
        assertEquals("5.5", GlucoseUnit.MMOL.format(100.0))
        assertEquals(5.55, GlucoseUnit.MMOL.from(100.0), 0.01)
    }
}
