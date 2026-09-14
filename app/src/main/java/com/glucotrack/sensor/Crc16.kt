package com.glucotrack.sensor

/**
 * CRC-16/X-25 (reflected CCITT, polynomial 0x8408) as used by Libre FRAM integrity fields.
 *
 * The table is computed at class-init rather than hard-coded, so there is no opportunity for a
 * transcription error; [Crc16Test] pins a few entries against the published table.
 */
internal object Crc16 {

    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        c
    }

    /** Computes the CRC over [length] bytes of [data] starting at [offset]. */
    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor TABLE[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        // The sensor stores the bit-reversed value.
        var reversed = 0
        repeat(16) {
            reversed = (reversed shl 1) or (crc and 1)
            crc = crc ushr 1
        }
        return reversed and 0xFFFF
    }
}
