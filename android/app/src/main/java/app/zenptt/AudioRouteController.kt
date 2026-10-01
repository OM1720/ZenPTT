// Selects, verifies, reports, and releases the Android communication audio device.
package app.zenptt

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AudioRouteController(
    context: Context,
    private val recordDiagnostic: (String) -> Unit,
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val _status = MutableStateFlow(AudioRouteStatus.Inactive)
    val status: StateFlow<AudioRouteStatus> = _status.asStateFlow()
    @Volatile private var headsetEnabled = false
    @Volatile private var selectedDevice: AudioDeviceInfo? = null
    var devicesChanged: (Boolean) -> Unit = {}
    private val routeMutex = Mutex()

    fun setHeadsetEnabled(enabled: Boolean) {
        headsetEnabled = enabled
        selectedDevice = null
        recordDiagnostic("policy headset_enabled=$enabled")
    }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            recordDiagnostic("devices_added ${addedDevices.joinToString { deviceSummary(it) }}")
            devicesChanged(false)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            recordDiagnostic("devices_removed ${removedDevices.joinToString { deviceSummary(it) }}")
            devicesChanged(removedDevices.any { it.id == selectedDevice?.id })
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun setStatus(status: AudioRouteStatus) {
        _status.value = status
    }

    suspend fun prepare(operation: String, newPhrase: Boolean = true): Boolean = routeMutex.withLock {
        _status.value = AudioRouteStatus.Preparing
        try {
            val startedAt = SystemClock.elapsedRealtime()
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val devices = audioManager.availableCommunicationDevices
            val bluetooth = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            val previous = selectedDevice?.takeIf { old -> devices.any { it.id == old.id } }
            var selected = if (!newPhrase && previous != null) previous else
                (if (headsetEnabled) bluetooth else null)
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val alreadySelected = selected != null && audioManager.communicationDevice?.id == selected.id
            val result = if (alreadySelected) true else selected?.let(audioManager::setCommunicationDevice)
            var routeReady = if (alreadySelected) {
                true
            } else if (selected != null && result == true) {
                withTimeoutOrNull(ROUTE_SELECTION_TIMEOUT_MS) {
                    while (audioManager.communicationDevice?.id != selected?.id) delay(ROUTE_POLL_INTERVAL_MS)
                    true
                } ?: false
            } else {
                false
            }
            if (!routeReady && headsetEnabled && selected?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                recordDiagnostic("$operation bluetooth_unavailable fallback=phone")
                selected = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val phone = selected
                routeReady = phone != null && audioManager.setCommunicationDevice(phone) &&
                    (withTimeoutOrNull(ROUTE_SELECTION_TIMEOUT_MS) {
                        while (audioManager.communicationDevice?.id != phone.id) delay(ROUTE_POLL_INTERVAL_MS)
                        true
                    } ?: false)
            }
            recordDiagnostic(
                "$operation route requested=${deviceSummary(selected)} initial_result=${result ?: "unavailable"} " +
                    "ready=$routeReady elapsed=${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "current=${deviceSummary(audioManager.communicationDevice)}",
            )
            if (selected != null && result != true) Log.w(TAG, "${operation}_route_unavailable")
            if (selected != null && !routeReady) Log.w(TAG, "${operation}_route_timeout")
            _status.value = if (routeReady) {
                routeStatus(audioManager.communicationDevice ?: selected)
            } else {
                AudioRouteStatus.Unavailable
            }
            selectedDevice = if (routeReady) selected else null
            routeReady
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _status.value = AudioRouteStatus.Unavailable
            recordDiagnostic("$operation route_error ${error.javaClass.simpleName}:${error.message}")
            Log.e(TAG, "${operation}_route_failed", error)
            false
        }
    }

    fun preferredInput(): AudioDeviceInfo? {
        val type = when (selectedDevice?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceInfo.TYPE_BLE_HEADSET
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_MIC
            else -> return null
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == type }
    }

    fun preferredOutput(): AudioDeviceInfo? = selectedDevice

    fun inputMatches(device: AudioDeviceInfo?): Boolean =
        device != null && device.id == preferredInput()?.id

    suspend fun verifyOutput(device: () -> AudioDeviceInfo?): Boolean =
        withTimeoutOrNull(ROUTE_SELECTION_TIMEOUT_MS) {
            while (device() == null) delay(ROUTE_POLL_INTERVAL_MS)
            device()?.id == selectedDevice?.id && selectedDevice != null
        } ?: false

    fun clear(
        operation: String,
        clearedStatus: AudioRouteStatus,
        failureStatus: AudioRouteStatus,
    ) {
        runCatching {
            audioManager.clearCommunicationDevice()
            selectedDevice = null
            audioManager.mode = AudioManager.MODE_NORMAL
            _status.value = clearedStatus
            recordDiagnostic("$operation route_cleared")
        }.onFailure {
            _status.value = failureStatus
            recordDiagnostic("$operation route_clear_error ${it.javaClass.simpleName}:${it.message}")
            Log.e(TAG, "${operation}_route_reset_failed", it)
        }
    }

    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    fun debugReportFields(): List<String> {
        val allDevices = runCatching {
            (audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) +
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .distinctBy(AudioDeviceInfo::getId)
                .joinToString { deviceSummary(it) }
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val communicationDevices = runCatching {
            audioManager.availableCommunicationDevices.joinToString { deviceSummary(it) }
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val current = runCatching { deviceSummary(audioManager.communicationDevice) }
            .getOrElse { "unavailable:${it.javaClass.simpleName}" }
        return listOf(
            "audio.mode=${audioManager.mode}",
            "audio.current_communication_device=$current",
            "audio.communication_devices=${communicationDevices.ifEmpty { "none" }}",
            "audio.all_devices=${allDevices.ifEmpty { "none" }}",
        )
    }

    fun currentDeviceSummary(): String = deviceSummary(audioManager.communicationDevice)

    fun deviceSummary(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        return "id=${device.id},type=${deviceType(device.type)},name=${device.productName}," +
            "source=${device.isSource},sink=${device.isSink}"
    }

    private fun routeStatus(device: AudioDeviceInfo?): AudioRouteStatus = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> AudioRouteStatus.BluetoothHeadset
        else -> AudioRouteStatus.Phone
    }

    private fun deviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built_in_mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        else -> type.toString()
    }

    private companion object {
        const val TAG = "ZenPTT.Audio"
        const val ROUTE_SELECTION_TIMEOUT_MS = 3_000L
        const val ROUTE_POLL_INTERVAL_MS = 25L
    }
}
