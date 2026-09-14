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

            val auth = Libre2Crypto.usefulFunction(uid, CMD_ACTIVATE, Libre2Crypto.SECRET)
            val payload = byteArrayOf(CMD_ACTIVATE.toByte()) + auth
            retrying { nfcv.transceive(abbottCommand(uid, payload)) }
                ?: return ScanResult.Failure("The sensor refused the start command. Try again.")

            // Re-read so the caller sees the post-activation state rather than assuming success.
            read(tag)
        } catch (e: IOException) {
            ScanResult.Failure("Lost contact with the sensor during activation. Try again.")
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
            } catch (e: IOException) {
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
        const val CMD_ACTIVATE = 0x1B

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
