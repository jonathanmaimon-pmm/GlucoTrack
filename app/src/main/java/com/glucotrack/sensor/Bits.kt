package com.glucotrack.sensor

/**
 * Reads [bitCount] bits out of [buffer], starting at [bitOffset] bits past [byteOffset].
 *
 * Libre glucose records are bit-packed and not byte-aligned, so every field in a 6-byte record
 * is extracted through this helper. Bits are little-endian within each byte.
 */
internal fun readBits(buffer: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int): Int {
    if (bitCount == 0) return 0
    var res = 0
    for (i in 0 until bitCount) {
        val totalBitOffset = byteOffset * 8 + bitOffset + i
        val byteIndex = totalBitOffset / 8
        val bit = totalBitOffset % 8
        if (totalBitOffset >= 0 && byteIndex < buffer.size &&
            ((buffer[byteIndex].toInt() ushr bit) and 0x1) == 1
        ) {
            res = res or (1 shl i)
        }
    }
    return res
}
