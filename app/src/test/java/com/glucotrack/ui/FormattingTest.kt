package com.glucotrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingTest {

    /**
     * The real sensor reports a maximum life past its nominal 14 days. Reading that raw value
     * produced "Day 1 of 14" beside "About 14 days 9 hours left" on the first real scan.
     */
    @Test
    fun `time left never exceeds the advertised wear period`() {
        val maxLife = 20880 // 14 days 12 hours, as reported by the sensor
        val age = 180       // three hours in

        assertEquals("Day 1 of 14", formatSensorDay(age, maxLife))
        assertEquals("About 13 days 21 hours left", formatSensorRemaining(age, maxLife))
    }

    @Test
    fun `a brand new sensor reports the full period`() {
        assertEquals("Day 1 of 14", formatSensorDay(0, 20880))
        assertEquals("About 14 days 0 hours left", formatSensorRemaining(0, 20880))
    }

    @Test
    fun `day counter does not run past the last day`() {
        // Inside the grace margin the sensor is still returning data, but it is day 14, not 15.
        assertEquals("Day 14 of 14", formatSensorDay(20500, 20880))
    }

    @Test
    fun `an expired sensor says so instead of counting negative`() {
        assertEquals("Expired — time to replace it", formatSensorRemaining(20880, 20880))
        assertEquals("Expired — time to replace it", formatSensorRemaining(99999, 20880))
    }

    @Test
    fun `the final hours read in hours rather than zero days`() {
        assertEquals("About 5 hours left", formatSensorRemaining(20160 - 300, 20880))
        assertEquals("About 1 hour left", formatSensorRemaining(20160 - 60, 20880))
        assertEquals("Less than an hour left", formatSensorRemaining(20160 - 10, 20880))
    }

    @Test
    fun `units are singular where that reads correctly`() {
        assertEquals("About 1 day 1 hour left", formatSensorRemaining(20160 - 1500, 20880))
    }

    @Test
    fun `an exactly fourteen day sensor still reads consistently`() {
        assertEquals("Day 1 of 14", formatSensorDay(180, 20160))
        assertEquals("About 13 days 21 hours left", formatSensorRemaining(180, 20160))
    }

    @Test
    fun `trend arrows follow the direction of change`() {
        assertEquals("→", trendArrow(0.0))
        assertEquals("↗", trendArrow(1.5))
        assertEquals("↑", trendArrow(2.5))
        assertEquals("↓", trendArrow(-2.5))
        assertEquals("–", trendArrow(null))
    }

    @Test
    fun `elapsed time reads naturally`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", formatElapsed(now, now))
        assertEquals("1 min ago", formatElapsed(now - 60_000, now))
        assertEquals("45 min ago", formatElapsed(now - 45 * 60_000, now))
        assertEquals("2 h ago", formatElapsed(now - 120 * 60_000, now))
        assertEquals("3 h 20 min ago", formatElapsed(now - 200 * 60_000, now))
    }

    @Test
    fun `rate is expressed per ten minutes with a sign`() {
        assertTrue(formatRate(-0.2).startsWith("-2 mg/dL"))
        assertTrue(formatRate(0.5).startsWith("+5 mg/dL"))
    }
}
