package com.glucotrack.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.streamingDataStore by preferencesDataStore(name = "glucotrack_streaming")

/**
 * What a BLE streaming session needs to reconnect.
 *
 * All of it comes from the NFC tap that switched streaming on. Losing any of it means streaming
 * cannot resume without another tap, which is why it is persisted rather than held in memory.
 */
data class StreamingSession(
    val macAddress: String,
    val uid: ByteArray,
    val patchInfo: ByteArray,
    val unlockCode: Int,
    /**
     * How many sessions have been opened with this unlock code.
     *
     * The sensor refuses an unlock payload it has already accepted, so this must increase on
     * every connection and must survive the app being killed. Persisting it late rather than
     * early would let a crash replay a payload and lock us out until the next NFC tap.
     */
    val unlockCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamingSession) return false
        return macAddress == other.macAddress &&
            uid.contentEquals(other.uid) &&
            patchInfo.contentEquals(other.patchInfo) &&
            unlockCode == other.unlockCode &&
            unlockCount == other.unlockCount
    }

    override fun hashCode(): Int {
        var r = macAddress.hashCode()
        r = 31 * r + uid.contentHashCode()
        r = 31 * r + patchInfo.contentHashCode()
        r = 31 * r + unlockCode
        r = 31 * r + unlockCount
        return r
    }
}

class StreamingStore(private val context: Context) {

    val session: Flow<StreamingSession?> = context.streamingDataStore.data.map { prefs ->
        val mac = prefs[MAC] ?: return@map null
        val uid = prefs[UID]?.fromHex() ?: return@map null
        val patch = prefs[PATCH]?.fromHex() ?: return@map null
        StreamingSession(
            macAddress = mac,
            uid = uid,
            patchInfo = patch,
            unlockCode = prefs[CODE] ?: return@map null,
            unlockCount = prefs[COUNT] ?: 0,
        )
    }

    suspend fun save(session: StreamingSession) {
        context.streamingDataStore.edit {
            it[MAC] = session.macAddress
            it[UID] = session.uid.toHex()
            it[PATCH] = session.patchInfo.toHex()
            it[CODE] = session.unlockCode
            it[COUNT] = session.unlockCount
        }
    }

    /** Bumps the session counter. Called before connecting, never after. */
    suspend fun nextUnlockCount(): Int {
        var next = 0
        context.streamingDataStore.edit {
            next = (it[COUNT] ?: 0) + 1
            it[COUNT] = next
        }
        return next
    }

    suspend fun clear() {
        context.streamingDataStore.edit { it.clear() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? =
        if (length % 2 != 0) null
        else runCatching {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()

    private companion object {
        val MAC = stringPreferencesKey("mac")
        val UID = stringPreferencesKey("uid")
        val PATCH = stringPreferencesKey("patch_info")
        val CODE = intPreferencesKey("unlock_code")
        val COUNT = intPreferencesKey("unlock_count")
    }
}
