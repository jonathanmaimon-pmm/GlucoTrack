package com.glucotrack.sensor

import android.nfc.Tag
import android.nfc.tech.NfcV
import java.io.IOException

/**
 * Reads a FreeStyle Libre 2 over NFC (ISO 15693 / NfcV).
 *
 * The sensor is a passive tag: it only answers while the phone's antenna is physically over it,
 * which on most handsets is a small area near the top of the back. Every exchange is therefore
 * retried for a short window, because a momentary wobble breaks the field and surfaces as an
 * [IOException] rather than as a clean error.
 */
class NfcSensorReader {

    /** Reads and decodes the sensor. Blocking — call from a background dispatcher. */
    fun read(tag: Tag): ScanResult {
        val nfcv = NfcV.get(tag)
            ?: return ScanResult.Failure("That tag isn't a Libre sensor.")

        return try {
            nfcv.connect()
            val uid = tag.id
            if (uid.size != 8) {
                return ScanResult.Failure("Unexpected tag ID length (${uid.size}).")
            }

            val patchInfo = readPatchInfo(nfcv, uid)
                ?: return ScanResult.Failure("Couldn't read the sensor's ID block. Try again.")

            val family = SensorFamily.fromPatchInfo(patchInfo)
            if (family != SensorFamily.LIBRE2 && family != SensorFamily.LIBRE1) {
                return ScanResult.Failure(
                    "This looks like a $family sensor, which this app doesn't decode yet."
                )
            }

            val raw = readFram(nfcv, uid)
                ?: return ScanResult.Failure("Couldn't read the full sensor memory. Hold the phone still against the sensor.")

            // Libre 1 returns plaintext; Libre 2 is encrypted unless the sensor was unlocked.
            val fram = if (Libre2Crypto.looksDecrypted(raw)) {
                raw
            } else {
                Libre2Crypto.decryptFram(uid, patchInfo, raw)
            }

            FramParser.parse(uid, patchInfo, fram, System.currentTimeMillis())
        } catch (e: IOException) {
            ScanResult.Failure("Lost contact with the sensor. Hold the phone still and try again.")
        } catch (e: SecurityException) {
            ScanResult.Failure("Lost contact with the sensor. Hold the phone still and try again.")
        } catch (e: IllegalArgumentException) {
            ScanResult.Failure(e.message ?: "The sensor returned data this app couldn't read.")
        } finally {
            runCatching { nfcv.close() }
        }
    }

    /**
     * Starts an unstarted sensor.
     *
     * A Libre 2 warms up for the first hour after activation and returns no usable glucose during
     * that window. Activation is one-way: once started, the 14-day clock runs whether or not
     * anything reads the sensor.
     */
    fun activate(tag: Tag): ScanResult {
        val nfcv = NfcV.get(tag) ?: return ScanResult.Failure("That tag isn't a Libre sensor.")
        return try {
            nfcv.connect()
            val uid = tag.id
            if (uid.size != 8) return ScanResult.Failure("Unexpected tag ID length (${uid.size}).")

            val patchInfo = readPatchInfo(nfcv, uid)
                ?: return ScanResult.Failure("Couldn't read the sensor's ID block. Try again.")

            val auth = Libre2Crypto.usefulFunction(uid, Libre2Crypto.CMD_ACTIVATE, Libre2Crypto.SECRET)
            val payload = byteArrayOf(Libre2Crypto.CMD_ACTIVATE.toByte()) + auth
            retrying { nfcv.transceive(abbottCommand(uid, payload)) }
                ?: return ScanResult.Failure("The sensor refused the start command. Try again.")

            // Re-read so the caller sees the post-activation state rather than assuming success.
            read(tag)
        } catch (e: IOException) {
            ScanResult.Failure("Lost contact with the sensor during activation. Try again.")
        } catch (e: SecurityException) {
            ScanResult.Failure("Lost contact with the sensor during activation. Try again.")
        } finally {
            runCatching { nfcv.close() }
        }
    }

    /**
     * Switches on BLE streaming and returns the sensor's Bluetooth address.
     *
     * The sensor broadcasts a reading a minute once this is enabled, which removes both the
     * tapping and the eight-hour deadline for collecting history. [unlockCode] is chosen by the
     * caller and must be stored: every later BLE session has to present a payload derived from
     * it, so losing it means the sensor cannot be reconnected to without enabling streaming
     * again over NFC.
     *
     * Enabling streaming does not disturb NFC reading — a tap keeps working either way, which
     * is what makes this safe to try on a sensor already in use.
     */
    fun enableStreaming(tag: Tag, unlockCode: Int): StreamingResult {
        val nfcv = NfcV.get(tag) ?: return StreamingResult.Failure("That tag isn't a Libre sensor.")
        return try {
            nfcv.connect()
            val uid = tag.id
            if (uid.size != 8) return StreamingResult.Failure("Unexpected tag ID length (${uid.size}).")

            val patchInfo = readPatchInfo(nfcv, uid)
                ?: return StreamingResult.Failure("Couldn't read the sensor's ID block. Try again.")

            val params = byteArrayOf(
                (unlockCode and 0xFF).toByte(),
                ((unlockCode ushr 8) and 0xFF).toByte(),
                ((unlockCode ushr 16) and 0xFF).toByte(),
                ((unlockCode ushr 24) and 0xFF).toByte(),
            )
            // This command authenticates against the patch info rather than the fixed secret.
            val secret = (
                ((patchInfo[4].toInt() and 0xFF) or ((patchInfo[5].toInt() and 0xFF) shl 8)) xor
                    ((params[0].toInt() and 0xFF) or ((params[1].toInt() and 0xFF) shl 8))
                ) and 0xFFFF
            val auth = Libre2Crypto.usefulFunction(uid, Libre2Crypto.CMD_ENABLE_STREAMING, secret)
            val payload = byteArrayOf(Libre2Crypto.CMD_ENABLE_STREAMING.toByte()) + params + auth

            val reply = retrying { nfcv.transceive(abbottCommand(uid, payload)) }
                ?: return StreamingResult.Failure("The sensor refused the streaming command. Try again.")

            // Flags byte, then the six address bytes in reverse order.
            if (reply.size != 7) {
                return StreamingResult.Failure("The sensor didn't report a Bluetooth address.")
            }
            val address = reply.copyOfRange(1, 7).reversedArray()
            StreamingResult.Success(
                macAddress = address.joinToString(":") { "%02X".format(it) },
                unlockCode = unlockCode,
                patchInfo = patchInfo,
                uid = uid,
            )
        } catch (e: IOException) {
            StreamingResult.Failure("Lost contact with the sensor. Hold the phone still and try again.")
        } catch (e: SecurityException) {
            StreamingResult.Failure("Lost contact with the sensor. Hold the phone still and try again.")
        } finally {
            runCatching { nfcv.close() }
        }
    }

    /** Builds an Abbott vendor-specific ISO 15693 frame: flags, 0xA1, manufacturer code, payload. */
    private fun abbottCommand(uid: ByteArray, payload: ByteArray): ByteArray =
        byteArrayOf(FLAG_HIGH_DATA_RATE, CMD_ABBOTT, uid[6]) + payload

    /** Reads the 6-byte patch info that identifies the sensor family and seeds decryption. */
    private fun readPatchInfo(nfcv: NfcV, uid: ByteArray): ByteArray? {
        val reply = retrying {
            nfcv.transceive(byteArrayOf(FLAG_HIGH_DATA_RATE, CMD_ABBOTT, uid[6]))
        } ?: return null
        // Byte 0 is the ISO 15693 response flags.
        if (reply.size < 7) return null
        return reply.copyOfRange(1, 7)
    }

    /**
     * Reads all 43 memory blocks.
     *
     * Blocks are fetched three at a time to keep the number of round trips low; 43 is not a
     * multiple of three, so the final block is fetched on its own.
     */
    private fun readFram(nfcv: NfcV, uid: ByteArray): ByteArray? {
        val fram = ByteArray(Libre2Crypto.FRAM_SIZE)
        var block = 0
        while (block < Libre2Crypto.BLOCK_COUNT) {
            val count = minOf(BLOCKS_PER_READ, Libre2Crypto.BLOCK_COUNT - block)
            val cmd = byteArrayOf(
                FLAG_HIGH_DATA_RATE,
                CMD_READ_MULTIPLE_BLOCKS,
                block.toByte(),
                (count - 1).toByte(),
            )
            val reply = retrying { nfcv.transceive(cmd) } ?: return null
            // 1 flags byte + 8 bytes per block.
            if (reply.size != 1 + count * 8) return null
            reply.copyInto(fram, block * 8, 1, 1 + count * 8)
            block += count
        }
        return fram
    }

    /**
     * Retries a tag exchange while the sensor stays in range.
     *
     * Returns null once the window expires, so callers surface a readable message instead of an
     * exception the user can do nothing with.
     */
    private fun retrying(exchange: () -> ByteArray): ByteArray? {
        val deadline = System.currentTimeMillis() + RETRY_WINDOW_MS
        while (true) {
            try {
                return exchange()
            } catch (e: Exception) {
                // IOException is the tag moving out of range mid-exchange. SecurityException is
                // Android rejecting a tag handle it considers stale, which happens routinely
                // when a handheld phone drifts off a sensor worn on an arm. Both mean "try
                // again"; anything else is a real fault and is rethrown.
                if (e !is IOException && e !is SecurityException) throw e
                if (System.currentTimeMillis() > deadline) return null
                try {
                    Thread.sleep(RETRY_PAUSE_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    private companion object {
        /** ISO 15693 request flag: high data rate, non-addressed. */
        const val FLAG_HIGH_DATA_RATE: Byte = 0x02
        const val CMD_READ_MULTIPLE_BLOCKS: Byte = 0x23
        /** Abbott vendor-specific command; the sub-command travels in the payload. */
        const val CMD_ABBOTT: Byte = 0xA1.toByte()

        const val BLOCKS_PER_READ = 3

        /**
         * How long to keep retrying a single exchange before giving up.
         *
         * NfcV exposes no per-transceive timeout the way IsoDep and NfcA do, so this window is
         * the only bound on how long a wobbly tap keeps trying.
         */
        const val RETRY_WINDOW_MS = 2000L
        const val RETRY_PAUSE_MS = 50L
    }
}
