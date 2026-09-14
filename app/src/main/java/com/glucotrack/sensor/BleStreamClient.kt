package com.glucotrack.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID

/** What the streaming connection is doing, reported so the UI never has to guess. */
sealed interface StreamState {
    data object Disconnected : StreamState
    data object Connecting : StreamState
    /** Connected and authenticated; readings should start arriving within a minute. */
    data object Listening : StreamState
    data class Failed(val reason: String) : StreamState
}

/**
 * Receives the reading a Libre 2 broadcasts once a minute.
 *
 * The sensor only talks to one connected device, and only after being handed an unlock payload
 * derived from the code it was given over NFC. Each connection must present a payload with a
 * fresh session counter, so [nextUnlockCount] is called once per connection attempt.
 *
 * Nothing here is required for the app to work: NFC scanning is unaffected whether streaming is
 * running, broken, or never set up. That is deliberate — a flaky Bluetooth stack should never
 * cost someone their glucose history.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT; see StreamingService.
class BleStreamClient(
    private val context: Context,
    private val nextUnlockCount: suspend () -> Int,
    private val onReading: (ByteArray) -> Unit,
    private val onState: (StreamState) -> Unit,
) {

    private var gatt: BluetoothGatt? = null
    private var session: com.glucotrack.data.StreamingSession? = null

    /** Notifications arrive as 20 + 18 + 8 bytes; this accumulates one packet. */
    private val buffer = ArrayList<Byte>(PACKET_SIZE)

    private var unlockPayload: ByteArray? = null

    /**
     * Opens a connection.
     *
     * [unlockCount] is taken before connecting rather than after success: replaying a counter the
     * sensor already accepted locks streaming out until the next NFC tap, so burning one on a
     * failed attempt is much the lesser problem.
     */
    fun connect(session: com.glucotrack.data.StreamingSession, unlockCount: Int) {
        disconnect()
        this.session = session
        unlockPayload = Libre2Crypto.streamingUnlockPayload(
            uid = session.uid,
            patchInfo = session.patchInfo,
            enableTime = session.unlockCode,
            unlockCount = unlockCount,
        )

        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onState(StreamState.Failed("Bluetooth is off."))
            return
        }

        val device = runCatching { adapter.getRemoteDevice(session.macAddress) }.getOrNull()
        if (device == null) {
            onState(StreamState.Failed("Couldn't find the sensor's Bluetooth address."))
            return
        }

        onState(StreamState.Connecting)
        // autoConnect, because the sensor only advertises around the minute it has data and is
        // otherwise unreachable. A direct connect would simply fail most of the time.
        gatt = device.connectGatt(context, true, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        buffer.clear()
        onState(StreamState.Disconnected)
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    buffer.clear()
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    buffer.clear()
                    // autoConnect keeps trying in the background, so this is normal between
                    // broadcasts rather than an error.
                    onState(StreamState.Connecting)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState(StreamState.Failed("Couldn't read the sensor's Bluetooth services."))
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                onState(StreamState.Failed("This device isn't broadcasting as a Libre sensor."))
                return
            }
            val login = service.getCharacteristic(LOGIN_UUID)
            val payload = unlockPayload
            if (login == null || payload == null) {
                onState(StreamState.Failed("The sensor didn't offer the expected connection."))
                return
            }
            writeCharacteristic(g, login, payload)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != LOGIN_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Most often a session counter the sensor has already seen.
                onState(StreamState.Failed("The sensor rejected the connection. Re-enable streaming with an NFC scan."))
                return
            }
            val data = g.getService(SERVICE_UUID)?.getCharacteristic(DATA_UUID)
            if (data == null) {
                onState(StreamState.Failed("The sensor didn't offer a data channel."))
                return
            }
            g.setCharacteristicNotification(data, true)
            val cccd = data.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            onState(StreamState.Listening)
        }

        // API 33 introduced the value-carrying overload; the old one still fires below it.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == DATA_UUID) accumulate(value)
        }

        @Deprecated("Superseded on API 33; still delivered on older releases.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == DATA_UUID
            ) {
                characteristic.value?.let { accumulate(it) }
            }
        }
    }

    /**
     * Collects notification fragments into one packet.
     *
     * A 20-byte fragment starts a new packet: the sensor always sends 20 + 18 + 8, so a fragment
     * of that size means the previous packet was truncated and should be abandoned rather than
     * glued onto this one.
     */
    private fun accumulate(fragment: ByteArray) {
        if (fragment.size == FIRST_FRAGMENT_SIZE) buffer.clear()
        fragment.forEach { buffer.add(it) }

        if (buffer.size >= PACKET_SIZE) {
            val packet = ByteArray(PACKET_SIZE) { buffer[it] }
            buffer.clear()
            onReading(packet)
        } else if (buffer.size > PACKET_SIZE) {
            buffer.clear()
        }
    }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = value
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(characteristic)
            }
        }
    }

    private fun writeDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                g.writeDescriptor(descriptor)
            }
        }
    }

    private companion object {
        /** Abbott's custom GATT service and its two characteristics. */
        val SERVICE_UUID: UUID = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        val LOGIN_UUID: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        val DATA_UUID: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")

        /** Standard client characteristic configuration descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val PACKET_SIZE = Libre2Crypto.BLE_PACKET_SIZE
        const val FIRST_FRAGMENT_SIZE = 20
    }
}
