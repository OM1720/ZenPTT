// Owns validated settings, persistence, and settings-related asynchronous UI state.
package app.zenptt

import app.zenptt.headset.*

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ChannelSettingsController(
    private val _state: MutableStateFlow<ChannelUiState>,
    private val addressStore: ConnectionPreferences,
    private val healthClient: ServerHealthClient,
    private val applyPowerSaveTimeout: (Int) -> Unit,
) {
    private val healthCheckGeneration = AtomicInteger()
    private val diagnosticUploadGeneration = AtomicInteger()
    private val uiErrorGeneration = AtomicInteger()

    init {
        applyPowerSaveTimeout(_state.value.powerSaveTimeoutMinutes.toInt())
    }

    fun setServerAddress(value: String) {
        invalidateServerCheck()
        _state.update { it.copy(serverAddress = value, uiError = null) }
    }

    fun setChannelCode(value: String) = _state.update { it.copy(channelCode = value, uiError = null) }

    fun setHeadsetSettings(value: HeadsetSettings): Boolean {
        if (!addressStore.saveHeadsetSettings(value)) {
            reportError(UiErrorTarget.General, "Could not save the setup. Your previous setup is unchanged.")
            return false
        }
        _state.update { it.copy(headsetSettings = value) }
        return true
    }

    fun headsetChangePending(value: Boolean) = _state.update { it.copy(headsetChangePending = value) }

    fun headsetSetupSaved() = _state.update { it.copy(headsetSetupSaved = it.headsetSetupSaved + 1) }

    fun reportError(target: UiErrorTarget, message: String) = _state.update {
        it.copy(uiError = UiError(uiErrorGeneration.incrementAndGet(), target, message))
    }

    fun consumeUiError(id: Int) = _state.update {
        if (it.uiError?.id == id) it.copy(uiError = null) else it
    }

    fun invalidateServerCheck() {
        healthCheckGeneration.incrementAndGet()
        _state.update { it.copy(serverCheckStatus = null) }
    }

    fun setPowerSaveTimeoutMinutes(value: String) {
        _state.update { it.copy(powerSaveTimeoutMinutes = value, uiError = null) }
        val minutes = InputValidator.powerSaveTimeoutMinutes(value) ?: return
        addressStore.savePowerSaveTimeoutMinutes(minutes)
        applyPowerSaveTimeout(minutes)
    }

    fun applyChannelCode(value: String): String? {
        val channel = InputValidator.channelCode(value)
        if (channel == null) {
            reportError(
                UiErrorTarget.Frequency,
                if (value.isBlank()) "Enter a frequency" else CHANNEL_CODE_ERROR,
            )
            return null
        }
        addressStore.saveLastChannel(channel)
        _state.update {
            it.copy(
                channelCode = channel,
                frequencyChoices = frequencyChoices(listOf(channel) + it.frequencyChoices),
                uiError = null,
            )
        }
        return channel
    }

    fun applySettings(
        serverAddress: String,
        powerSaveTimeoutMinutes: String,
    ): ServerSettings? {
        val settings = validatedServerSettings(serverAddress, powerSaveTimeoutMinutes) ?: return null
        addressStore.save(settings.address)
        addressStore.savePowerSaveTimeoutMinutes(settings.timeoutMinutes)
        applyPowerSaveTimeout(settings.timeoutMinutes)
        _state.update {
            it.copy(
                serverAddress = settings.address,
                powerSaveTimeoutMinutes = settings.timeoutMinutes.toString(),
                uiError = null,
                serverCheckStatus = null,
            )
        }
        return settings
    }

    fun pingServer(value: String = _state.value.serverAddress) {
        val address = InputValidator.serverAddress(value)
        if (address == null) {
            reportError(UiErrorTarget.Server, "Enter a valid server address")
            return
        }
        val generation = healthCheckGeneration.incrementAndGet()
        _state.update { it.copy(uiError = null, serverCheckStatus = "Checking server...") }
        healthClient.check(address) { result ->
            if (healthCheckGeneration.get() != generation) return@check
            val status = result.fold(
                onSuccess = { "Server available" },
                onFailure = { error ->
                    val detail = error.message?.lineSequence()?.firstOrNull()?.take(160)
                    if (detail.isNullOrBlank()) "Server unavailable" else "Server unavailable: $detail"
                },
            )
            _state.update { it.copy(serverCheckStatus = status) }
        }
    }

    fun diagnosticUploadStarted(): Int {
        val generation = diagnosticUploadGeneration.incrementAndGet()
        _state.update {
            it.copy(
                diagnosticUploading = true,
                diagnosticUploadStatus = "Sending diagnostics...",
                diagnosticReportCode = null,
                diagnosticCopyStatus = null,
            )
        }
        return generation
    }

    fun diagnosticUploadFinished(generation: Int, result: Result<String>) {
        if (diagnosticUploadGeneration.get() != generation) return
        val (status, reportCode) = result.fold(
            onSuccess = { "Report sent" to it },
            onFailure = { "Unable to send diagnostics" to null },
        )
        _state.update {
            it.copy(
                diagnosticUploading = false,
                diagnosticUploadStatus = status,
                diagnosticReportCode = reportCode,
            )
        }
    }

    fun diagnosticCodeCopied() = _state.update {
        if (it.diagnosticReportCode == null) it else it.copy(diagnosticCopyStatus = "Code copied")
    }

    fun validatedConnection(echo: Boolean): ConnectionConfig? {
        val settings = validatedServerSettings(
            _state.value.serverAddress,
            _state.value.powerSaveTimeoutMinutes,
        ) ?: return null
        val channel = if (echo) ECHO_CHANNEL else InputValidator.channelCode(_state.value.channelCode)
        if (channel == null) {
            reportError(
                UiErrorTarget.Frequency,
                if (_state.value.channelCode.isBlank()) "Enter a frequency" else CHANNEL_CODE_ERROR,
            )
            return null
        }
        _state.update { it.copy(uiError = null) }
        return ConnectionConfig(
            settings.address,
            channel,
            settings.timeoutMinutes,
        )
    }

    private fun validatedServerSettings(
        serverAddress: String,
        powerSaveTimeoutMinutes: String,
    ): ServerSettings? {
        val address = InputValidator.serverAddress(serverAddress)
        if (address == null) {
            reportError(UiErrorTarget.Server, "Enter a valid server address")
            return null
        }
        val timeoutMinutes = InputValidator.powerSaveTimeoutMinutes(powerSaveTimeoutMinutes)
        if (timeoutMinutes == null) {
            reportError(UiErrorTarget.PowerSave, "Enter power save timeout from 1 to 1440 minutes")
            return null
        }
        return ServerSettings(address, timeoutMinutes)
    }

    fun persistConnection(config: ConnectionConfig) {
        addressStore.saveLastChannel(config.channel)
        addressStore.save(config.address)
        addressStore.savePowerSaveTimeoutMinutes(config.timeoutMinutes)
        applyPowerSaveTimeout(config.timeoutMinutes)
        _state.update {
            it.copy(
                serverAddress = config.address,
                channelCode = config.channel,
                frequencyChoices = frequencyChoices(listOf(config.channel) + it.frequencyChoices),
                powerSaveTimeoutMinutes = config.timeoutMinutes.toString(),
                uiError = null,
            )
        }
    }

    data class ConnectionConfig(
        val address: String,
        val channel: String,
        val timeoutMinutes: Int,
    )

    data class ServerSettings(
        val address: String,
        val timeoutMinutes: Int,
    )
}

internal fun ConnectionPreferences.loadInitialChannelState(): ChannelUiState = ChannelUiState(
    serverAddress = load().ifBlank { DEFAULT_SERVER_ADDRESS },
    channelCode = InputValidator.channelCode(loadLastChannel().orEmpty()) ?: ECHO_CHANNEL,
    frequencyChoices = loadFrequencyChoices(),
    powerSaveTimeoutMinutes = loadPowerSaveTimeoutMinutes().toString(),
    headsetSettings = loadHeadsetSettings(),
)
