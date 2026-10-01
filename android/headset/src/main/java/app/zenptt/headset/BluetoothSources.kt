// Opens bounded SPP and BLE connections and reports their input events.
package app.zenptt.headset

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

internal interface SourceConnection {
    val connectedDevice: BluetoothDevice?
    fun close()
}

@SuppressLint("MissingPermission")
internal class SppConnection(
    context: Context,
    scope: CoroutineScope,
    setup: HeadsetSetup,
    hints: Boolean,
    private val byteLimit: Int,
    private val ready: () -> Unit,
    private val event: (String, ByteArray) -> Unit,
    private val failed: (String) -> Unit,
) : SourceConnection {
    @Volatile private var closed = false
    @Volatile private var socket: BluetoothSocket? = null
    override val connectedDevice: BluetoothDevice? get() = socket?.takeIf { !closed && it.isConnected }?.remoteDevice
    private val job: Job

    init {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val preferences = context.getSharedPreferences("headset_transport", Context.MODE_PRIVATE)
        job = scope.launch(Dispatchers.IO) {
            try {
                val device = if (setup.autoSelect) {
                    val paired = adapter?.bondedDevices.orEmpty()
                    paired.firstOrNull { it.name?.contains("BM008", true) == true }
                        ?: paired.firstOrNull { it.name?.contains("PTT", true) == true && it.uuids?.any { uuid -> uuid.uuid.toString() == setup.service } == true }
                } else adapter?.getRemoteDevice(setup.device)
                if (device == null) { failed("device_unavailable"); return@launch }
                val preferred = preferences.getBoolean("insecure", true)
                for (insecure in listOf(preferred, !preferred)) {
                    if (closed) return@launch
                    val candidate = if (insecure) device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(setup.service))
                        else device.createRfcommSocketToServiceRecord(UUID.fromString(setup.service))
                    socket = candidate
                    if (closed) { candidate.close(); return@launch }
                    try {
                        candidate.connect()
                    } catch (_: Exception) {
                        runCatching { candidate.close() }
                        if (socket === candidate) socket = null
                        continue
                    }
                    preferences.edit().putBoolean("insecure", insecure).apply()
                    if (closed) { candidate.close(); return@launch }
                    ready()
                    val framer = SppFramer(hints, event)
                    val buffer = ByteArray(512)
                    var received = 0L
                    while (!closed) {
                        val count = candidate.inputStream.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > byteLimit) { failed("source_byte_limit"); return@launch }
                        if (count > 0) framer.feed(buffer.copyOf(count))
                    }
                    break
                }
                if (!closed) failed("spp_disconnected")
            } catch (error: Exception) {
                if (!closed) failed("spp_${error.javaClass.simpleName}")
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        job.cancel()
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class BleConnection(
    private val context: Context,
    private val setup: HeadsetSetup,
    private val ready: () -> Unit,
    private val event: (String, ByteArray) -> Unit,
    private val failed: (String) -> Unit,
    private val serviceFound: (String, String) -> Unit,
) : SourceConnection {
    @Volatile private var closed = false
    private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    override val connectedDevice: BluetoothDevice? get() = gatt?.device?.takeIf { !closed && connected }
    private val pending = ArrayDeque<BluetoothGattCharacteristic>()
    private val enabled = mutableSetOf<String>()
    private var current: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                failed("ble_disconnected")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices()) {
                failed("ble_discovery_failed")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) { failed("ble_discovery_failed"); return }
            val choices = gatt.services.flatMap { service -> service.characteristics }
                .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                .filter { setup.characteristic.isEmpty() || (it.uuid.toString() == setup.characteristic && it.service.uuid.toString() == setup.service) }
            // Ambiguous duplicate UUIDs cannot be restored reliably from the saved address.
            val unique = choices.groupBy { it.uuid }.values.filter { it.size == 1 }.map { it.single() }
            if (unique.size > MAX_CHARACTERISTICS) { failed("ble_characteristic_limit"); return }
            pending.addAll(unique)
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed) return
            val characteristic = current
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                enabled += characteristic.uuid.toString()
                serviceFound(characteristic.uuid.toString(), characteristic.service.uuid.toString())
            }
            subscribeNext(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            deliver(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(characteristic, value)
        }
    }

    init {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            gatt = adapter?.getRemoteDevice(setup.device)?.connectGatt(context, false, callback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()))
            if (gatt == null) failed("ble_unavailable")
        } catch (error: RuntimeException) { failed("ble_${error.javaClass.simpleName}") }
    }

    @Synchronized
    private fun subscribeNext(gatt: BluetoothGatt) {
        if (closed) return
        while (pending.isNotEmpty()) {
            val characteristic = pending.removeFirst()
            val descriptor = characteristic.getDescriptor(CCCD) ?: continue
            if (!gatt.setCharacteristicNotification(characteristic, true)) continue
            current = characteristic
            descriptor.value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (gatt.writeDescriptor(descriptor)) return
        }
        current = null
        if (enabled.isEmpty()) failed("ble_no_notifications") else ready()
    }

    @Synchronized
    private fun deliver(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (!closed && characteristic.uuid.toString() in enabled) event(characteristic.uuid.toString(), value.copyOf())
    }

    override fun close() {
        closed = true
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        const val MAX_CHARACTERISTICS = 32
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
