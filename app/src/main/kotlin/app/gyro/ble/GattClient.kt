package app.gyro.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.gyro.protocol.BleTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Suspend-friendly wrapper around [BluetoothGatt].
 *
 * Android allows one outstanding GATT operation at a time; every operation here takes [opMutex] and
 * waits for its callback, so callers can simply call them in sequence. Notifications are delivered in
 * order through an unbounded channel. Callers must hold BLUETOOTH_CONNECT (checked by the UI).
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val device: BluetoothDevice) {

    class Notification(val characteristic: UUID, val value: ByteArray)

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    private val notificationChannel = Channel<Notification>(Channel.UNLIMITED)

    /** Notifications in arrival order; closed when the client is closed. */
    val incoming: ReceiveChannel<Notification> get() = notificationChannel

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    @Volatile private var connectResult: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesResult: CompletableDeferred<Boolean>? = null
    @Volatile private var mtuResult: CompletableDeferred<Int>? = null
    @Volatile private var writeResult: CompletableDeferred<Boolean>? = null
    @Volatile private var descriptorResult: CompletableDeferred<Boolean>? = null

    var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectResult?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                connectResult?.complete(false)
                servicesResult?.complete(false)
                mtuResult?.complete(mtu)
                writeResult?.complete(false)
                descriptorResult?.complete(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuResult?.complete(mtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+ delivers the value directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notificationChannel.trySend(Notification(c.uuid, value.copyOf()))
        }

        // Android 8–12 path; on 13+ only the three-argument overload is called.
        @Deprecated("Deprecated in Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = c.value ?: return
            notificationChannel.trySend(Notification(c.uuid, value.copyOf()))
        }
    }

    suspend fun connect(timeoutMs: Long = 15_000): Boolean {
        val result = CompletableDeferred<Boolean>()
        connectResult = result
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        return withTimeoutOrNull(timeoutMs) { result.await() } ?: false
    }

    suspend fun discoverServices(): List<BluetoothGattService> {
        return opMutex.withLock {
            val g = gatt ?: return emptyList()
            val result = CompletableDeferred<Boolean>()
            servicesResult = result
            if (!g.discoverServices()) return emptyList()
            withTimeoutOrNull(10_000) { result.await() }
            g.services.orEmpty()
        }
    }

    /** Larger MTU lets KingSong F-series send their long BMS frames in one notification. */
    suspend fun requestMtu(size: Int): Int {
        return opMutex.withLock {
            val g = gatt ?: return mtu
            val result = CompletableDeferred<Int>()
            mtuResult = result
            if (!g.requestMtu(size)) return mtu
            withTimeoutOrNull(3_000) { result.await() } ?: mtu
        }
    }

    fun requestHighPriority() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    @Suppress("DEPRECATION")
    suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean {
        return opMutex.withLock {
            val g = gatt ?: return false
            val c = g.getService(service)?.getCharacteristic(characteristic) ?: return false
            if (!g.setCharacteristicNotification(c, true)) return false
            // Some HM-10 clones notify without a CCCD descriptor.
            val descriptor = c.getDescriptor(BleTransport.CLIENT_CONFIG_DESCRIPTOR) ?: return true
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val result = CompletableDeferred<Boolean>()
            descriptorResult = result
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                g.writeDescriptor(descriptor)
            }
            if (!started) return false
            withTimeoutOrNull(3_000) { result.await() } ?: false
        }
    }

    /**
     * Writes one chunk. No-response writes still get a callback when the stack accepted the packet;
     * some stacks skip it, so a missing callback after a second counts as sent.
     */
    @Suppress("DEPRECATION")
    suspend fun write(service: UUID, characteristic: UUID, bytes: ByteArray): Boolean {
        return opMutex.withLock {
            val g = gatt ?: return false
            val c = g.getService(service)?.getCharacteristic(characteristic) ?: return false
            val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            val result = CompletableDeferred<Boolean>()
            writeResult = result
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = bytes
                g.writeCharacteristic(c)
            }
            if (!started) return false
            withTimeoutOrNull(1_000) { result.await() } ?: true
        }
    }

    fun close() {
        _connected.value = false
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        notificationChannel.close()
    }
}
