// Lists connected Bluetooth and external input devices for headset setup.
package app.zenptt.headset

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

data class HeadsetDevice(
    val id: String,
    val name: String,
    val address: String = "",
    val descriptor: String = "",
    val spp: Boolean = false,
    val ble: Boolean = false,
)

@SuppressLint("MissingPermission")
internal class HeadsetDiscovery(
    private val context: Context,
    private val ownConnection: () -> BluetoothDevice?,
    private val changed: (List<HeadsetDevice>) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    private var listener: BluetoothProfile.ServiceListener? = null
    private var devices = emptyList<HeadsetDevice>()
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (listener != null) main.postDelayed(this, CONNECTION_POLL_MS)
        }
    }

    fun start() {
        stop()
        changed(emptyList())
        val current = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (listener !== this) { adapter?.closeProfileProxy(profile, proxy); return }
                profiles[profile] = proxy
                refresh()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (listener !== this) return
                profiles.remove(profile)
                refresh()
            }
        }
        listener = current
        val ids = mutableListOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        if (Build.VERSION.SDK_INT >= 33) ids += BluetoothProfile.LE_AUDIO
        ids.forEach { runCatching { adapter?.getProfileProxy(context, current, it) } }
        poll.run()
    }

    fun findConnected(id: String): HeadsetDevice? {
        refresh()
        return devices.firstOrNull { it.id == id }
    }

    private fun refresh() {
        if (listener == null) return
        val connected = profiles.values.flatMap { runCatching { it.connectedDevices }.getOrDefault(emptyList()) } +
            runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() }.getOrDefault(emptyList()) +
            listOfNotNull(ownConnection())
        val bluetooth = connected.distinctBy { it.address }.map { device ->
            HeadsetDevice("bt:${device.address}", runCatching { device.name }.getOrNull()?.takeIf(String::isNotBlank) ?: "Unnamed headset",
                address = device.address, spp = runCatching { device.type != BluetoothDevice.DEVICE_TYPE_LE }.getOrDefault(false),
                ble = runCatching { device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }.getOrDefault(false))
        }
        val inputs = InputDevice.getDeviceIds().asIterable().mapNotNull(InputDevice::getDevice)
            .filter { it.isExternal && !it.isVirtual }
            .map { HeadsetDevice("hid:${it.descriptor}", it.name, descriptor = it.descriptor) }
        val next = bluetooth + inputs
        if (next != devices) { devices = next; changed(devices) }
    }

    fun stop() {
        listener = null
        main.removeCallbacks(poll)
        profiles.forEach { (id, proxy) -> runCatching { adapter?.closeProfileProxy(id, proxy) } }
        profiles.clear()
        devices = emptyList()
    }

    private companion object { const val CONNECTION_POLL_MS = 1_000L }
}
