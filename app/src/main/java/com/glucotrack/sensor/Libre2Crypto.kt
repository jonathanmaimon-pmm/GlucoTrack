package com.glucotrack.sensor

/**
 * Decryption for FreeStyle Libre 2 sensor memory.
 *
 * A Libre 2 sensor stores its 344-byte FRAM encrypted under a key derived per 8-byte block from
 * the sensor's NFC UID and its "patch info" (the 6 bytes returned by NFC command 0xA1). This is a
 * port of the reverse-engineered scheme as implemented in DiaBLE / LibreTools, which the
 * open-source diabetes community has used against these sensors for years.
 *
 * Every constant here is load-bearing: a wrong value does not fail loudly, it produces a
 * plausible-looking but wrong glucose reading. They are pinned by [Libre2CryptoTest] and must not
 * be "tidied up".
 *
 * All arithmetic is on 16-bit unsigned values held in [Int]; [u16] masks after every operation
 * that can overflow.
 */
internal object Libre2Crypto {

    private val KEY = intArrayOf(0xA0C5, 0x6860, 0x0000, 0x14C6)

    /** Fixed secret used as the default `y` argument of [usefulFunction]. */
    const val SECRET = 0x1B6A

    private fun u16(v: Int) = v and 0xFFFF

    /** Little-endian 16-bit read of `data[offset]`, `data[offset + 1]`. */
    private fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    /**
     * The cipher's round function: shift right two bits, then conditionally mix in a key word for
     * each of the two bits shifted out.
     */
    private fun op(value: Int): Int {
        var res = value ushr 2
        if (value and 1 != 0) res = res xor KEY[1]
        if (value and 2 != 0) res = res xor KEY[0]
        return u16(res)
    }

    /** Eight rounds of [op] over four 16-bit words, returned in reverse order. */
    fun processCrypto(input: IntArray): IntArray {
        val r0 = u16(op(input[0]) xor input[3])
        val r1 = u16(op(r0) xor input[2])
        val r2 = u16(op(r1) xor input[1])
        val r3 = u16(op(r2) xor input[0])
        val r4 = op(r3)
        val r5 = op(u16(r4 xor r0))
        val r6 = op(u16(r5 xor r1))
        val r7 = op(u16(r6 xor r2))

        val f1 = u16(r0 xor r4)
        val f2 = u16(r1 xor r5)
        val f3 = u16(r2 xor r6)
        val f4 = u16(r3 xor r7)

        return intArrayOf(f4, f3, f2, f1)
    }

    /** Builds the cipher input words from the sensor UID and two caller-supplied parameters. */
    fun prepareVariables(uid: ByteArray, x: Int, y: Int): IntArray {
        val s1 = u16(le16(uid, 4) + x + y)
        val s2 = u16(le16(uid, 2) + KEY[2])
        val s3 = u16(le16(uid, 0) + x * 2)
        val s4 = u16(0x241A xor KEY[3])
        return intArrayOf(s1, s2, s3, s4)
    }

    /** Variant word setup used by the BLE streaming handshake. */
    fun prepareVariables2(uid: ByteArray, i1: Int, i2: Int, i3: Int, i4: Int): IntArray {
        val s1 = u16(le16(uid, 4) + i1)
        val s2 = u16(le16(uid, 2) + i2)
        val s3 = u16(le16(uid, 0) + i3 + KEY[2])
        val s4 = u16(i4 + KEY[3])
        return intArrayOf(s1, s2, s3, s4)
    }

    /**
     * Derives the 4-byte authentication token the sensor expects appended to NFC commands
     * below 0x20 (and used as a building block of the BLE streaming key).
     */
    fun usefulFunction(uid: ByteArray, x: Int, y: Int): ByteArray {
        val blockKey = processCrypto(prepareVariables(uid, x, y))
        val r1 = u16(blockKey[0] xor 0x4163)
        val r2 = u16(blockKey[1] xor 0x4344)
        return byteArrayOf(
            (r1 and 0xFF).toByte(),
            ((r1 ushr 8) and 0xFF).toByte(),
            (r2 and 0xFF).toByte(),
            ((r2 ushr 8) and 0xFF).toByte(),
        )
    }

    /**
     * Decrypts all 43 blocks of a Libre 2 FRAM image.
     *
     * @param uid the 8-byte NFC tag UID
     * @param patchInfo the 6 bytes returned by NFC command 0xA1
     * @param data the 344 encrypted bytes read from the tag
     * @return a new 344-byte array of plaintext FRAM
     */
    fun decryptFram(uid: ByteArray, patchInfo: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= FRAM_SIZE) { "FRAM must be $FRAM_SIZE bytes, got ${data.size}" }
        require(uid.size >= 8) { "UID must be 8 bytes, got ${uid.size}" }
        require(patchInfo.size >= 6) { "patch info must be 6 bytes, got ${patchInfo.size}" }

        // For Libre 2 every block uses the same second argument.
        val arg = u16(le16(patchInfo, 4) xor 0x44)
        val out = ByteArray(FRAM_SIZE)

        for (block in 0 until BLOCK_COUNT) {
            val blockKey = processCrypto(prepareVariables(uid, block, arg))
            val base = block * 8
            for (word in 0 until 4) {
                out[base + word * 2] =
                    (data[base + word * 2].toInt() xor (blockKey[word] and 0xFF)).toByte()
                out[base + word * 2 + 1] =
                    (data[base + word * 2 + 1].toInt() xor ((blockKey[word] ushr 8) and 0xFF)).toByte()
            }
        }
        return out
    }

    /**
     * Derives the 12-byte payload that unlocks a BLE streaming session.
     *
     * Written to the login characteristic straight after connecting. [enableTime] is the 32-bit
     * code sent when streaming was switched on over NFC, and [unlockCount] increments once per
     * session — the sensor rejects a payload it has already seen, so both must be persisted
     * across app restarts or streaming stops working until the next NFC enable.
     */
    fun streamingUnlockPayload(
        uid: ByteArray,
        patchInfo: ByteArray,
        enableTime: Int,
        unlockCount: Int,
    ): ByteArray {
        val time = enableTime + unlockCount
        val b = byteArrayOf(
            (time and 0xFF).toByte(),
            ((time ushr 8) and 0xFF).toByte(),
            ((time ushr 16) and 0xFF).toByte(),
            ((time ushr 24) and 0xFF).toByte(),
        )

        val ad = usefulFunction(uid, CMD_ACTIVATE, SECRET)
        val ed = usefulFunction(uid, CMD_ENABLE_STREAMING, u16((enableTime and 0xFFFF) xor le16(patchInfo, 4)))

        val t2 = processCrypto(
            prepareVariables2(
                uid,
                u16(le16(ed, 0) xor le16(b, 2)),
                le16(ad, 0),
                u16(le16(ed, 2) xor le16(b, 0)),
                le16(ad, 2),
            )
        )

        val t31 = Crc16.compute(
            byteArrayOf(
                0xC1.toByte(), 0xC4.toByte(), 0xC3.toByte(), 0xC0.toByte(),
                0xD4.toByte(), 0xE1.toByte(), 0xE7.toByte(), 0xBA.toByte(),
                (t2[0] and 0xFF).toByte(), ((t2[0] ushr 8) and 0xFF).toByte(),
            ),
            0, 10,
        )
        val t32 = Crc16.compute(wordsToBytes(t2[1], t2[2], t2[3]), 0, 6)
        val t33 = Crc16.compute(byteArrayOf(ad[0], ad[1], ad[2], ad[3], ed[0], ed[1]), 0, 6)
        val t34 = Crc16.compute(byteArrayOf(ed[2], ed[3], b[0], b[1], b[2], b[3]), 0, 6)

        val t4 = processCrypto(prepareVariables2(uid, t31, t32, t33, t34))
        return b + wordsToBytes(t4[0], t4[1], t4[2], t4[3])
    }

    private fun wordsToBytes(vararg words: Int): ByteArray {
        val out = ByteArray(words.size * 2)
        words.forEachIndexed { i, w ->
            out[i * 2] = (w and 0xFF).toByte()
            out[i * 2 + 1] = ((w ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Decrypts one 46-byte BLE notification into its 44-byte payload.
     *
     * The keystream depends on the sensor UID and on the packet's own two-byte header, so each
     * packet is encrypted differently. The trailing CRC is verified here rather than by the
     * caller: a corrupted packet must never reach the glucose parser, where it would decode into
     * a plausible-looking reading.
     *
     * @return the decrypted payload, or null if the packet failed its checksum.
     */
    fun decryptBle(uid: ByteArray, data: ByteArray): ByteArray? {
        if (data.size != BLE_PACKET_SIZE) return null

        val d = usefulFunction(uid, CMD_ACTIVATE, SECRET)
        val x = (le16(d, 0) xor le16(d, 2)) or 0x63
        val y = le16(data, 0) xor 0x63

        val keystream = ByteArray(64)
        var k = processCrypto(prepareVariables(uid, x, y))
        for (round in 0 until 8) {
            for (w in 0 until 4) {
                keystream[round * 8 + w * 2] = (k[w] and 0xFF).toByte()
                keystream[round * 8 + w * 2 + 1] = ((k[w] ushr 8) and 0xFF).toByte()
            }
            k = processCrypto(k)
        }

        val out = ByteArray(BLE_PAYLOAD_SIZE)
        for (i in 0 until BLE_PAYLOAD_SIZE) {
            out[i] = (data[i + 2].toInt() xor keystream[i].toInt()).toByte()
        }

        val stored = (out[42].toInt() and 0xFF) or ((out[43].toInt() and 0xFF) shl 8)
        return if (stored == Crc16.compute(out, 0, 42)) out else null
    }

    /**
     * True if [fram] looks like plaintext, judged by the header CRC.
     *
     * Libre 2 FRAM arrives encrypted, but a sensor unlocked over NFC (or a Libre 1) returns it in
     * clear, so decryption is applied conditionally rather than unconditionally.
     */
    fun looksDecrypted(fram: ByteArray): Boolean {
        if (fram.size < FRAM_SIZE) return false
        val stored = (fram[0].toInt() and 0xFF) or ((fram[1].toInt() and 0xFF) shl 8)
        return stored == Crc16.compute(fram, 2, 22)
    }

    const val BLOCK_COUNT = 43
    const val FRAM_SIZE = BLOCK_COUNT * 8

    /** NFC sub-command numbers, needed here because they seed the streaming key derivation. */
    const val CMD_ACTIVATE = 0x1B
    const val CMD_ENABLE_STREAMING = 0x1E

    /** A BLE notification carries a 2-byte header plus 44 encrypted bytes. */
    const val BLE_PACKET_SIZE = 46
    const val BLE_PAYLOAD_SIZE = 44
}
