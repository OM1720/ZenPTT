// Hosts Compose UI and bridges permissions, the foreground service, updates, and diagnostics.
package app.zenptt

import app.zenptt.headset.*

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal fun shouldAutoConnect(state: ChannelUiState, explicitlyDisconnected: Boolean): Boolean =
    !explicitlyDisconnected && state.currentChannel == null && state.channelCode.isNotBlank()

internal fun shouldConnectAfterFrequencySubmit(
    state: ChannelUiState,
    normalizedFrequency: String?,
): Boolean = state.currentChannel == null &&
    normalizedFrequency != null &&
    normalizedFrequency != state.channelCode

internal enum class AccessiblePttAction { Start, Stop, Disabled }

internal fun accessiblePttAction(state: ChannelUiState): AccessiblePttAction = when {
    state.pendingPtt -> AccessiblePttAction.Stop
    state.status == SessionStatus.PlayingEcho && state.playbackInterruptible ->
        AccessiblePttAction.Start
    state.status == SessionStatus.Ready ||
        state.status == SessionStatus.Receiving ||
        state.currentChannel == null ||
        state.status == SessionStatus.Connecting ||
        state.status == SessionStatus.Reconnecting ||
        state.status == SessionStatus.ConnectionError -> AccessiblePttAction.Start
    state.status == SessionStatus.Requesting ||
        state.status == SessionStatus.Transmitting ||
        state.status == SessionStatus.Busy -> AccessiblePttAction.Stop
    else -> AccessiblePttAction.Disabled
}

internal fun shouldReleaseAccessiblePtt(state: ChannelUiState): Boolean = when {
    state.pendingPtt -> false
    else -> state.currentChannel == null || state.status in setOf(
        SessionStatus.Connecting,
        SessionStatus.Reconnecting,
        SessionStatus.ConnectionError,
        SessionStatus.Releasing,
        SessionStatus.PlayingEcho,
    )
}

class MainActivity : ComponentActivity() {
    private val uiState = MutableStateFlow(ChannelUiState())
    private val hardwareStatus = MutableStateFlow("")
    private val pttSetup = MutableStateFlow<HeadsetSetupState?>(null)
    private var pttSetupJob: Job? = null
    private val audioRouteStatus = MutableStateFlow(AudioRouteStatus.Inactive)
    private val batteryOptimizationWarning = MutableStateFlow(false)
    private val appUpdateCoordinator by lazy {
        AppUpdateCoordinator(appUpdateClient, File(cacheDir, "updates"))
    }
    private val appUpdateClient: AppUpdateClient = OkHttpAppUpdateClient()
    private var service: PttForegroundService? = null
    private var stateJob: Job? = null
    private var hardwareJob: Job? = null
    private var audioRouteJob: Job? = null
    private var bound = false
    private var pendingEcho: Boolean? = null
    private var autoConnectAttempted = false
    private var explicitlyDisconnected = false
    private var accessibilityPttLatched = false
    private var localUiErrorId = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = (binder as PttForegroundService.LocalBinder).service
            service = connectedService
            connectedService.cancelPttSetup()
            pttSetupJob?.cancel()
            pttSetupJob = lifecycleScope.launch {
                connectedService.pttSetup.collect { pttSetup.value = it }
            }
            stateJob?.cancel()
            hardwareJob?.cancel()
            audioRouteJob?.cancel()
            stateJob = lifecycleScope.launch {
                connectedService.state.collect { next ->
                    uiState.value = next
                    if (accessibilityPttLatched && shouldReleaseAccessiblePtt(next)) {
                        releaseAccessibilityPtt()
                    }
                }
            }
            hardwareJob = lifecycleScope.launch {
                connectedService.hardwareStatus.collect { hardwareStatus.value = it }
            }
            audioRouteJob = lifecycleScope.launch {
                connectedService.audioRouteStatus.collect { audioRouteStatus.value = it }
            }
            if (!autoConnectAttempted) {
                autoConnectAttempted = true
                val initialState = connectedService.state.value
                if (shouldAutoConnect(initialState, explicitlyDisconnected)) {
                    requestSessionStart(echo = false)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            pttSetupJob?.cancel()
            pttSetup.value = null
            stateJob?.cancel()
            hardwareJob?.cancel()
            audioRouteJob?.cancel()
            service = null
            accessibilityPttLatched = false
            uiState.value = uiState.value.copy(
                currentChannel = null,
                status = SessionStatus.ConnectionError,
                uiError = UiError(
                    id = ++localUiErrorId,
                    target = UiErrorTarget.General,
                    message = "Background service stopped unexpectedly.",
                ),
            )
        }
    }

    private val sessionPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        service?.permissionChanged()
        val echo = pendingEcho ?: return@registerForActivityResult
        pendingEcho = null
        if (hasRequiredPermissions()) {
            startSession(echo)
        } else {
            reportError(
                "Allow microphone, nearby devices, and notifications for background operation.",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.WHITE,
                android.graphics.Color.WHITE,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.WHITE,
                android.graphics.Color.WHITE,
            ),
        )
        explicitlyDisconnected = savedInstanceState?.getBoolean(EXPLICIT_DISCONNECT_KEY) == true
        val store = SharedPreferencesConnectionPreferences(this)
        uiState.value = store.loadInitialChannelState()
        setContent {
            val state by uiState.collectAsStateWithLifecycle()
            val headsetStatus by hardwareStatus.collectAsStateWithLifecycle()
            val setupState by pttSetup.collectAsStateWithLifecycle()
            val showBatteryWarning by batteryOptimizationWarning.collectAsStateWithLifecycle()
            val updateState by appUpdateCoordinator.state.collectAsStateWithLifecycle()
            ZenPttApp(
                state = state,
                hardwareStatus = headsetStatus,
                pttSetup = setupState,
                showBatteryOptimizationWarning = showBatteryWarning,
                appUpdateState = updateState,
                appUpdateActions = AppUpdateActions(
                    check = ::checkForUpdates,
                    downloadAndInstall = ::downloadAndInstallUpdate,
                ),
                actions = ChannelActions(
                    submitFrequency = ::submitFrequency,
                    frequencyDraftChanged = { value -> service?.setChannelCode(value) },
                    toggleConnection = ::toggleConnection,
                    pttDown = ::pttDown,
                    pttUp = { service?.pttUp() },
                    toggleAccessiblePtt = ::toggleAccessiblePtt,
                    releaseAccessiblePtt = ::releaseAccessibilityPtt,
                    applySettings = ::applySettings,
                    serverDraftChanged = ::serverDraftChanged,
                    setHeadsetSettings = ::setHeadsetSettings,
                    beginPttSetup = {
                        requestPttBluetoothPermission()
                        service?.beginPttSetup(useProtocolHints = !intent.getBooleanExtra("headset_disable_protocol_hints", false))
                    },
                    selectHeadsetDevice = { service?.selectHeadsetDevice(it) },
                    confirmHeadsetTest = { service?.confirmHeadsetTest(it) },
                    cancelPttSetup = { service?.cancelPttSetup() },
                    nextSetupStep = { service?.nextSetupStep() },
                    retryPttSetup = { service?.retryPttSetup() },
                    choosePttBehavior = { service?.choosePttBehavior(it) },
                    savePttSetup = { service?.savePttSetup() },
                    consumeUiError = ::consumeUiError,
                    consumePttNotice = ::consumePttNotice,
                    pingServer = { address -> service?.pingServer(address) },
                    shareDebugInfo = ::shareDebugInfo,
                    sendDiagnosticReport = ::sendDiagnosticReport,
                    copyDiagnosticCode = ::copyDiagnosticCode,
                    openBatteryOptimizationSettings = ::openBatteryOptimizationSettings,
                ),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXPLICIT_DISCONNECT_KEY, explicitlyDisconnected)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, PttForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onResume() {
        super.onResume()
        val powerManager = getSystemService(PowerManager::class.java)
        batteryOptimizationWarning.value =
            !powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onStop() {
        service?.cancelPttSetup()
        pttSetupJob?.cancel()
        pttSetup.value = null
        releaseAccessibilityPtt()
        stateJob?.cancel()
        hardwareJob?.cancel()
        audioRouteJob?.cancel()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        service = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (service?.handleKeyEvent(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (service?.handleKeyEvent(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun pttDown() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            service?.pttDown()
        } else {
            service?.playPttRejected(
                "Allow microphone access, then release and press PTT again.",
            ) ?: reportError("Allow microphone access before using PTT.")
            sessionPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun toggleConnection() {
        val connectedService = service
        if (connectedService == null) {
            reportError("Background service is not ready. Try again.")
            return
        }
        val snapshot = connectedService.state.value
        when {
            snapshot.currentChannel == null -> {
                explicitlyDisconnected = false
                requestSessionStart(echo = false)
            }
            snapshot.status == SessionStatus.ConnectionError -> connectedService.reconnect()
            else -> {
                releaseAccessibilityPtt()
                explicitlyDisconnected = true
                PttForegroundService.disconnect(this)
            }
        }
    }

    private fun submitFrequency(value: String): Boolean {
        val connectedService = service
        if (connectedService == null) {
            reportError("Background service is not ready. Try again.")
            return false
        }
        val snapshot = connectedService.state.value
        val normalized = InputValidator.channelCode(value)
        val channelChanged = normalized != null && normalized != snapshot.channelCode
        val shouldConnect = shouldConnectAfterFrequencySubmit(snapshot, normalized)
        if (!connectedService.applyChannelCode(value)) return false
        if (channelChanged) {
            explicitlyDisconnected = false
            if (shouldConnect) requestSessionStart(echo = false)
        }
        return true
    }

    private fun toggleAccessiblePtt() {
        val connectedService = service ?: return
        when (accessiblePttAction(connectedService.state.value)) {
            AccessiblePttAction.Start -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    service?.playPttRejected(
                        "Allow microphone access, then release and press PTT again.",
                    ) ?: reportError("Allow microphone access before using PTT.")
                    sessionPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return
                }
                accessibilityPttLatched = true
                connectedService.accessibilityPttDown()
                val next = connectedService.state.value
                val announcement = if (next.status == SessionStatus.Busy) {
                    next.pttNotice?.message ?: "PTT unavailable. Double-tap to release, then try again."
                } else {
                    "PTT engaged, double-tap to finish"
                }
                window.decorView.announceForAccessibility(announcement)
            }
            AccessiblePttAction.Stop -> if (releaseAccessibilityPtt()) {
                window.decorView.announceForAccessibility("Transmission finished")
            }
            AccessiblePttAction.Disabled -> Unit
        }
    }

    private fun releaseAccessibilityPtt(): Boolean {
        if (!accessibilityPttLatched) return false
        accessibilityPttLatched = false
        service?.accessibilityPttUp()
        return true
    }

    private fun serverDraftChanged() {
        service?.invalidateServerCheck()
        appUpdateCoordinator.addressChanged()
    }

    private fun setHeadsetSettings(value: HeadsetSettings) {
        val connectedService = service
        if (connectedService != null) {
            connectedService.setHeadsetSettings(value)
        } else {
            reportError("Background service is not ready. Try again.")
            return
        }
        if (value.enabled) requestPttBluetoothPermission()
    }

    private fun requestPttBluetoothPermission() {
        val permissions = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) sessionPermissions.launch(permissions.toTypedArray())
    }

    private fun consumeUiError(id: Int) {
        service?.consumeUiError(id)
        if (uiState.value.uiError?.id == id) {
            uiState.value = uiState.value.copy(uiError = null)
        }
    }

    private fun applySettings(
        serverAddress: String,
        powerSaveTimeoutMinutes: String,
    ): Boolean {
        val connectedService = service
        if (connectedService == null) {
            reportError("Background service is not ready. Try again.")
            return false
        }
        val previousAddress = connectedService.state.value.serverAddress
        val applied = connectedService.applySettings(
            serverAddress,
            powerSaveTimeoutMinutes,
        )
        if (applied && previousAddress != connectedService.state.value.serverAddress) {
            appUpdateCoordinator.addressChanged()
        }
        return applied
    }

    private fun requestSessionStart(echo: Boolean) {
        val connectedService = service
        if (connectedService == null) {
            reportError("Background service is not ready. Try again.")
            return
        }
        if (!connectedService.prepareConnection(echo)) return
        if (hasRequiredPermissions()) {
            startSession(echo)
            return
        }
        pendingEcho = echo
        sessionPermissions.launch(requiredPermissions())
    }

    private fun startSession(echo: Boolean) {
        val state = service?.state?.value ?: return
        runCatching { PttForegroundService.connect(this, state, echo) }
            .onFailure {
                reportError(
                    "Unable to start background mode. Try again while the app is open.",
                )
            }
    }

    private fun reportError(
        message: String,
        target: UiErrorTarget = UiErrorTarget.General,
    ) {
        service?.reportError(target, message)
            ?: run {
                uiState.value = uiState.value.copy(
                    uiError = UiError(++localUiErrorId, target, message),
                )
            }
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (uiState.value.headsetSettings.enabled) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun checkForUpdates(value: String) {
        val address = validatedUpdateAddress(value) ?: return
        appUpdateCoordinator.check(address, installedVersionCode(), appVersion())
    }

    private fun consumePttNotice(id: Int) {
        service?.consumePttNotice(id)
        if (uiState.value.pttNotice?.id == id) {
            uiState.value = uiState.value.copy(pttNotice = null)
        }
    }

    private fun downloadAndInstallUpdate(value: String) {
        if (appUpdateCoordinator.state.value.available == null) return
        if (!packageManager.canRequestPackageInstalls()) {
            appUpdateCoordinator.showStatus(
                "Allow ZenPTT to install apps, then tap Install again",
            )
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        val address = validatedUpdateAddress(value) ?: return
        appUpdateCoordinator.downloadAndVerify(address) { downloaded ->
            runOnUiThread { openUpdateInstaller(downloaded) }
        }
    }

    private fun validatedUpdateAddress(value: String): String? {
        val address = InputValidator.serverAddress(value)
        if (address == null) {
            appUpdateCoordinator.addressChanged()
            appUpdateCoordinator.showStatus("Enter a valid server address")
        }
        return address
    }

    private fun openUpdateInstaller(update: DownloadedUpdate) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", update.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            appUpdateCoordinator.showStatus("Unable to open Android installer")
        }
    }

    private fun installedVersionCode(): Long =
        packageManager.getPackageInfo(packageName, 0).longVersionCode

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun diagnosticReport(): DiagnosticReport {
        val appVersion = appVersion()
        val androidVersion = Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
        val details = (service?.debugReport(appVersion, androidVersion)
            ?: "ZenPTT $appVersion\nservice=not bound") +
            "\nprocess.last_exit=" + lastProcessExitReason() +
            "\nbattery.settings=package:" + packageName +
            "\n" + appUpdateCoordinator.diagnosticReport()
        return DiagnosticReport.create(
            createdAtMs = System.currentTimeMillis(),
            appVersion = appVersion,
            androidVersion = androidVersion,
            networkStatus = networkIndicator(uiState.value.status),
            headsetStatus = headsetIndicator(hardwareStatus.value),
            audioRoute = audioRouteStatus.value.label,
            details = details,
        )
    }

    private fun sendDiagnosticReport() {
        val connectedService = service
        if (connectedService == null) {
            reportError("Background service is not ready. Try again.")
            return
        }
        connectedService.uploadDiagnosticReport(diagnosticReport())
    }
    private fun copyDiagnosticCode() {
        val code = uiState.value.diagnosticReportCode ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("ZenPTT report code", code),
        )
        service?.diagnosticCodeCopied()
            ?: run { uiState.value = uiState.value.copy(diagnosticCopyStatus = "Code copied") }
    }

    private fun shareDebugInfo() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ZenPTT diagnostic report")
            putExtra(Intent.EXTRA_TEXT, diagnosticReport().asText())
        }
        startActivity(Intent.createChooser(shareIntent, "Share debug info"))
    }
    private fun lastProcessExitReason(): String {
        return getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 1)
            .firstOrNull()
            ?.let {
                "reason=" + it.reason +
                    ", timestamp=" + it.timestamp +
                    ", importance=" + it.importance
            }
            ?: "none"
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private companion object {
        const val EXPLICIT_DISCONNECT_KEY = "explicit_disconnect"
    }
}
