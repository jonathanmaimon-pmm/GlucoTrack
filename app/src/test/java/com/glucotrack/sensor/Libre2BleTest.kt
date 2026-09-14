package com.glucotrack.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the BLE streaming path against the same independent reference used for the FRAM tests.
 *
 * [PACKET] is a synthetic notification built by encrypting a well-formed payload with the
 * published keystream: ten measurements, a sensor clock, and a valid trailing CRC.
 */
class Libre2BleTest {

    @Test
    fun `decryptBle recovers the payload and accepts its checksum`() {
        val payload = Libre2Crypto.decryptBle(UID, PACKET)
        assertNotNull("packet should decrypt and pass CRC", payload)
        assertEquals(44, payload!!.size)
        assertEquals(5000, (payload[40].toInt() and 0xFF) or ((payload[41].toInt() and 0xFF) shl 8))
    }

    @Test
    fun `a corrupted packet is rejected rather than decoded`() {
        // One flipped byte must fail the CRC. Passing it through would yield a plausible reading.
        val corrupted = PACKET.copyOf()
        corrupted[10] = (corrupted[10].toInt() xor 0xFF).toByte()
        assertNull(Libre2Crypto.decryptBle(UID, corrupted))
    }

    @Test
    fun `a wrong length packet is rejected`() {
        assertNull(Libre2Crypto.decryptBle(UID, PACKET.copyOf(45)))
        assertNull(Libre2Crypto.decryptBle(UID, ByteArray(0)))
    }

    @Test
    fun `a packet from a different sensor does not decrypt`() {
        val otherUid = UID.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(Libre2Crypto.decryptBle(otherUid, PACKET))
    }

    @Test
    fun `parse recovers trend and history at the documented offsets`() {
        val reading = parseFixture()
        assertEquals(5000, reading.wearTimeMinutes)
        assertEquals(7, reading.trend.size)
        assertEquals(3, reading.history.size)
        assertEquals(
            listOf(5000, 4998, 4996, 4994, 4993, 4988, 4985),
            reading.trend.map { it.minutesSinceStart },
        )
        assertEquals(
            listOf(4995, 4980, 4965),
            reading.history.map { it.minutesSinceStart },
        )
    }

    @Test
    fun `parse applies the calibration from the NFC scan`() {
        val reading = parseFixture()
        assertEquals(listOf(96, 98, 99, 100, 102, 103, 104), reading.trend.map { it.mgdl?.toInt() })
        assertEquals(listOf(106, 107, 108), reading.history.map { it.mgdl?.toInt() })
        assertEquals(96, reading.current?.mgdl?.toInt())
    }

    @Test
    fun `history records land on the fifteen minute grid`() {
        val reading = parseFixture()
        val ids = reading.history.map { it.minutesSinceStart }
        assertTrue(ids.all { it % 15 == 0 })
        assertEquals(15, ids[0] - ids[1])
    }

    @Test
    fun `timestamps run backwards from the moment the packet arrived`() {
        val receivedAt = 1_700_000_000_000L
        val reading = BleParser.parse(decrypted(), CALIBRATION, receivedAt)!!
        // The newest trend sample is the sensor's current minute.
        assertEquals(receivedAt, reading.trend[0].timestamp)
        assertEquals(receivedAt - 2 * 60_000L, reading.trend[1].timestamp)
    }

    @Test
    fun `without calibration no glucose is invented`() {
        val reading = BleParser.parse(decrypted(), CalibrationInfo(), 0L)!!
        assertTrue(reading.trend.all { it.mgdl == null })
        assertNull(reading.current)
    }

    @Test
    fun `streaming unlock payload matches the reference`() {
        assertEquals(
            byteArrayOf(0x2A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xF1.toByte(), 0xC6.toByte(), 0x3D.toByte(), 0x2A.toByte(), 0x95.toByte(), 0x7E.toByte(), 0x07.toByte(), 0x65.toByte()).toList(),
            Libre2Crypto.streamingUnlockPayload(UID, PATCH_INFO, 42, 0).toList(),
        )
    }

    @Test
    fun `unlock payload changes with the session counter`() {
        // The sensor refuses a payload it has already seen, so the count must feed the result.
        assertEquals(
            byteArrayOf(0x31.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xC8.toByte(), 0x67.toByte(), 0xB4.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0x8B.toByte(), 0xAC.toByte(), 0xAE.toByte()).toList(),
            Libre2Crypto.streamingUnlockPayload(UID, PATCH_INFO, 42, 7).toList(),
        )
        assertTrue(
            !Libre2Crypto.streamingUnlockPayload(UID, PATCH_INFO, 42, 0)
                .contentEquals(Libre2Crypto.streamingUnlockPayload(UID, PATCH_INFO, 42, 1))
        )
    }

    private fun decrypted(): ByteArray = Libre2Crypto.decryptBle(UID, PACKET)!!

    private fun parseFixture(): BleReading =
        BleParser.parse(decrypted(), CALIBRATION, 1_700_000_000_000L)!!

    private companion object {
        val UID = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte(), 0x9A.toByte(), 0xBC.toByte(), 0x07.toByte(), 0xE0.toByte())
        val PATCH_INFO = byteArrayOf(0x9D.toByte(), 0x08.toByte(), 0x30.toByte(), 0x01.toByte(), 0x5A.toByte(), 0xA5.toByte())
        val CALIBRATION = CalibrationInfo(i1 = 1, i2 = 500, i3 = 30, i4 = 5000, i6 = 6000)
        val PACKET = hex("34129e781be5955018ec483da1a8cd5bc5df70070dd35a80329c02eab5ddf1ddf7ea532b7acbe59a06cc9e96574b")

        fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
