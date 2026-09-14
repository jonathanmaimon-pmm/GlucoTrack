package com.glucotrack.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Libre 2 decoding pipeline against an independently generated reference.
 *
 * [ENCRYPTED_FRAM] is a synthetic but fully well-formed sensor image: valid CRCs over all three
 * regions, populated trend and history rings, and plausible calibration parameters. It was
 * produced by a separate implementation of the published algorithm, so these tests catch porting
 * mistakes in the Kotlin. That is the risk worth guarding: a wrong constant here does not throw,
 * it yields a reading that looks perfectly reasonable and is wrong.
 */
class Libre2DecodingTest {

    @Test
    fun `crc16 matches the published check value`() {
        val input = "123456789".toByteArray()
        assertEquals(0x89F6, Crc16.compute(input, 0, input.size))
    }

    @Test
    fun `processCrypto matches reference vector`() {
        assertArrayEqualsInt(
            intArrayOf(27009, 41408, 7684, 4216),
            Libre2Crypto.processCrypto(intArrayOf(1, 2, 3, 4)),
        )
    }

    @Test
    fun `usefulFunction matches reference vector`() {
        val expected = byteArrayOf(0xAE.toByte(), 0x73.toByte(), 0xCC.toByte(), 0x3B.toByte())
        val actual = Libre2Crypto.usefulFunction(UID, 0x1B, Libre2Crypto.SECRET)
        assertEquals(expected.toList(), actual.toList())
    }

    @Test
    fun `calibration tables are the expected size`() {
        // Indexed by the 10-bit i2 parameter, so both must hold exactly 1023 entries.
        assertEquals(1023, CalibrationTables.T1.size)
        assertEquals(1023, CalibrationTables.T2.size)
    }

    @Test
    fun `decryptFram recovers the plaintext image`() {
        val decrypted = Libre2Crypto.decryptFram(UID, PATCH_INFO, ENCRYPTED_FRAM)
        assertEquals(PLAIN_FRAM.toList(), decrypted.toList())
    }

    @Test
    fun `encrypted image is not mistaken for plaintext`() {
        assertTrue(Libre2Crypto.looksDecrypted(PLAIN_FRAM))
        assertTrue(!Libre2Crypto.looksDecrypted(ENCRYPTED_FRAM))
    }

    @Test
    fun `decrypted image passes all three CRC regions`() {
        assertTrue(FramParser.crcValid(Libre2Crypto.decryptFram(UID, PATCH_INFO, ENCRYPTED_FRAM)))
    }

    @Test
    fun `parse recovers sensor identity and age`() {
        val scan = parseFixture()
        assertEquals("3QKE7HNJM28", scan.serial)
        assertEquals(SensorFamily.LIBRE2, scan.family)
        assertEquals(SensorState.ACTIVE, scan.state)
        assertEquals(5000, scan.ageMinutes)
        assertEquals(14 * 24 * 60, scan.maxLifeMinutes)
    }

    @Test
    fun `parse recovers the trend ring in newest-first order`() {
        val scan = parseFixture()
        assertEquals(16, scan.trend.size)
        assertEquals(
            listOf(96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111),
            scan.trend.map { it.mgdl?.toInt() },
        )
        // Trend samples are one minute apart, counting back from the sensor's current age.
        assertEquals(5000, scan.trend[0].minutesSinceStart)
        assertEquals(4999, scan.trend[1].minutesSinceStart)
    }

    @Test
    fun `parse recovers the history ring at 15 minute spacing`() {
        val scan = parseFixture()
        assertEquals(32, scan.history.size)
        assertEquals(
            listOf(106, 108, 110, 112, 114, 116, 117, 119, 121, 123, 125, 127, 129, 131, 133, 135, 136, 138, 140, 142, 144, 146, 148, 150, 152, 154, 155, 157, 159, 161, 163, 165),
            scan.history.map { it.mgdl?.toInt() },
        )
        assertEquals(15, scan.history[0].minutesSinceStart - scan.history[1].minutesSinceStart)
    }

    @Test
    fun `current reading is the newest valid trend sample`() {
        val scan = parseFixture()
        assertNotNull(scan.current)
        assertEquals(96, scan.current!!.mgdl?.toInt())
    }

    @Test
    fun `trend rate carries the direction of change`() {
        // The trend list is newest-first, so the fixture reads 111 mg/dL sixteen minutes ago down
        // to 96 mg/dL now: falling at 1 mg/dL per minute. A positive result here would mean the
        // arrow on screen points the wrong way.
        val rate = parseFixture().trendRate
        assertNotNull(rate)
        assertEquals(-1.0, rate!!, 0.05)
    }

    @Test
    fun `timestamps run backwards from the scan time`() {
        val scannedAt = 1_700_000_000_000L
        val scan = parse(scannedAt)
        assertEquals(scannedAt, scan.trend[0].timestamp)
        assertEquals(scannedAt - 60_000L, scan.trend[1].timestamp)
    }

    @Test
    fun `unusable calibration yields no reading rather than a wrong one`() {
        // i2 of zero falls outside the lookup tables.
        val broken = CalibrationInfo(i1 = 1, i2 = 0, i3 = 30, i4 = 5000, i6 = 6000)
        assertNull(FramParser.factoryGlucose(650, 6000, 0, broken))
        // i4 == i3 would divide by zero.
        assertNull(FramParser.factoryGlucose(650, 6000, 0, broken.copy(i2 = 500, i4 = 30)))
    }

    @Test
    fun `a raw value of zero is reported as missing`() {
        val cal = CalibrationInfo(i1 = 1, i2 = 500, i3 = 30, i4 = 5000, i6 = 6000)
        assertNull(FramParser.factoryGlucose(0, 6000, 0, cal))
    }

    @Test
    fun `a short read is rejected rather than half-parsed`() {
        val result = FramParser.parse(UID, PATCH_INFO, ByteArray(100), 0L)
        assertTrue(result is ScanResult.Failure)
    }

    @Test
    fun `a corrupted image fails its integrity check`() {
        val corrupted = Libre2Crypto.decryptFram(UID, PATCH_INFO, ENCRYPTED_FRAM)
        corrupted[100] = (corrupted[100].toInt() xor 0xFF).toByte()
        assertTrue(!FramParser.crcValid(corrupted))
        assertTrue(FramParser.parse(UID, PATCH_INFO, corrupted, 0L) is ScanResult.Failure)
    }

    private fun parseFixture(): SensorScan = parse(1_700_000_000_000L)

    private fun parse(scannedAt: Long): SensorScan {
        val fram = Libre2Crypto.decryptFram(UID, PATCH_INFO, ENCRYPTED_FRAM)
        val result = FramParser.parse(UID, PATCH_INFO, fram, scannedAt)
        assertTrue("expected a successful parse, got $result", result is ScanResult.Success)
        return (result as ScanResult.Success).scan
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    private companion object {
        val UID = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte(), 0x9A.toByte(), 0xBC.toByte(), 0x07.toByte(), 0xE0.toByte())
        val PATCH_INFO = byteArrayOf(0x9D.toByte(), 0x08.toByte(), 0x30.toByte(), 0x01.toByte(), 0x5A.toByte(), 0xA5.toByte())

        val ENCRYPTED_FRAM = hex(
        "4b733e0b5dcc15ebceb94472adac20a13f7e2c91d159ea9e3cc7edf4f6438ee46694577a1b84370aa8ca2f7eff948c42" +
        "c77f50ed0063df0fcf23fcf9625309bdf8bfeb3feae0925b35912739de827263d32693d87205af2c67b79ec2ea6d1d24" +
        "8abc973304da72c8da90ec474bc850f28e5533d6204d20cf3c1564c8844c89dfab501b99d7452d2fdb0fea9c33551966" +
        "0abb950f54a3c52a78584664d7c8b650bc235a96850de1cc030d9690856f3af4e5ba33711de8e7bbbf92c50fdcb2f67b" +
        "91adc905756b058fc181f7703a7927b5cf456de051fc248942a5bcf81de79ef07eac7f7a8c21271eeef36e7f6831b357" +
        "df4711ecafc7cf1bc805e48184b6dc09c73304a0f4bcf68a181cc8a694df4db3feabcd466c5990fcfc3b715df43185f5" +
        "a73178acfa874d18f71de6d955946f2259d9dc493e118c1e0e86ff2f574072df483b143bc296f26f9a74cf4c31f6076b" +
        "753bb4af4d030d09"
        )

        val PLAIN_FRAM = hex(
        "c296a10f0300000000000000000000000000000000000000662a070da80200701700a302007017009e02007017009902" +
        "007017009402007017008f02007017008a0200701700d50200701700d00200701700cb0200701700c60200701700c102" +
        "00701700bc0200701700b70200701700b20200701700ad02007017003403007017002a03007017002003007017001603" +
        "007017000c0300701700020300701700f80200701700ee0200701700e40200701700da0200701700d00200701700c602" +
        "00701700bc0200701700f20300701700e80300701700de0300701700d40300701700ca0300701700c00300701700b603" +
        "00701700ac0300701700a203007017009803007017008e03007017008403007017007a03007017007003007017006603" +
        "007017005c03007017005203007017004803007017003e030070170088130100951300010000c04e0000000000000000" +
        "1e8813000000c05d"
        )

        fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
