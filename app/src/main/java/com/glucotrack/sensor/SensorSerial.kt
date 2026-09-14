package com.glucotrack.sensor

/**
 * Derives the printed sensor serial (e.g. `3MH00ABCDEF`) from the 8-byte NFC UID.
 *
 * The serial is a base-32 encoding of the last six UID bytes, reversed, prefixed by the sensor
 * family digit. Matching the printed serial is the easiest way for a user to confirm the app is
 * talking to the sensor they think it is.
 */
internal object SensorSerial {

    private val ALPHABET = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'C', 'D', 'E', 'F', 'G',
        'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
    )

    fun from(uid: ByteArray, family: SensorFamily): String {
        if (uid.size != 8) return ""
        // uid reversed, keeping the low six bytes: [uid5, uid4, uid3, uid2, uid1, uid0]
        val b = IntArray(6) { uid[5 - it].toInt() and 0xFF }

        val fiveBits = intArrayOf(
            b[0] shr 3,
            (b[0] shl 2) + (b[1] shr 6),
            b[1] shr 1,
            (b[1] shl 4) + (b[2] shr 4),
            (b[2] shl 1) + (b[3] shr 7),
            b[3] shr 2,
            (b[3] shl 3) + (b[4] shr 5),
            b[4],
            b[5] shr 3,
            b[5] shl 2,
        )

        val prefix = if (family == SensorFamily.UNKNOWN) "0" else family.code.toString()
        return fiveBits.fold(StringBuilder(prefix)) { acc, v ->
            acc.append(ALPHABET[v and 0x1F])
        }.toString()
    }
}
