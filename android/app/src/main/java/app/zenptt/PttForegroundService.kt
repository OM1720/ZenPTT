// Owns the active PTT session and its network, audio, headset, wake-lock, and notification lifecycle.
package app.zenptt

import app.zenptt.headset.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger

internal const val ACTIVE_PTT_NOTIFICATION_CHANNEL_ID = "active_ptt_session_v3"
internal const val ACTIVE_PTT_NOTIFICATION_ID = 1001
internal const val LEGACY_ACTIVE_PTT_NOTIFICATION_CHANNEL_ID = "active_ptt_session_v2"
private const val ACTION_DISCONNECT = "app.zenptt.action.DISCONNECT"

private const val OPEN_APP_REQUEST_CODE = 1
private const val DISCONNECT_REQUEST_CODE = 2

internal fun createActivePttNotificationChannel(notifications: NotificationManager) {
    notifications.createNotificationChannel(
        NotificationChannel(
            ACTIVE_PTT_NOTIFICATION_CHANNEL_ID,
            "Active PTT session",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shows when ZenPTT is connected in the background"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        },
    )
    notifications.deleteNotificationChannel(LEGACY_ACTIVE_PTT_NOTIFICATION_CHANNEL_ID)
}

internal fun activePttNotification(
    context: Context,
    state: ChannelUiState,
    connecting: Boolean = false,
): Notification {
    val openApp = PendingIntent.getActivity(
        context,
        OPEN_APP_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val disconnectSession = PendingIntent.getService(
        context,
        DISCONNECT_REQUEST_CODE,
        Intent(context, PttForegroundService::class.java).setAction(ACTION_DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val channel = state.currentChannel ?: state.channelCode.ifBlank { "PTT" }
    val status = if (connecting) SessionStatus.Connecting.label else state.status.label
    val network = if (connecting) {
        networkIndicator(SessionStatus.Connecting)
    } else {
        networkIndicator(state.status)
    }
    return Notification.Builder(context, ACTIVE_PTT_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_yinyang_notification)
        .setContentTitle("ZenPTT - $channel")
        .setContentText(state.pttError ?: "$status \u00b7 Network: $network")
        .setContentIntent(openApp)
        .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_ui_mic),
                "PTT",
                openApp,
            ).build(),
        )
        .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_ui_link_off),
                "Disconnect",
                disconnectSession,
            ).build(),
        )
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .build()
}

internal fun publishActivePttNotification(
    context: Context,
    notifications: NotificationManager,
    state: ChannelUiState,
) {
    notifications.notify(
        ACTIVE_PTT_NOTIFICATION_ID,
        activePttNotification(context, state),
    )
}

class PttForegroundService : Service() {
    inner class LocalBinder : Binder() {
        val service: PttForegroundService
            get() = this@PttForegroundService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: ChannelViewModel
    private lateinit var audio: AudioPipeline
    private lateinit var headset: HeadsetController
    private var hardwareSettings: HeadsetSettings? = null
    private var hardwareChannel: String? = null
    private var headsetChange: Job? = null
    private var savingHeadset = false
    private lateinit var pttWakeLock: PowerManager.WakeLock
    private val pttWakeLockAcquisitions = AtomicInteger()
    private val pttWakeLockFailures = AtomicInteger()
    private val diagnosticUploadClient: DiagnosticUploadClient = OkHttpDiagnosticUploadClient()
    private val pttInputs = PttInputLatch(::startPtt, ::stopPtt)
    private val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)
    private var foregroundStarted = false
    private var createdAtMs = 0L
    private var startCommandCount = 0

    val state: StateFlow<ChannelUiState>
        get() = session.state
    val hardwareStatus: StateFlow<String>
        get() = headset.status
    val pttSetup: StateFlow<HeadsetSetupState?>
        get() = headset.setup
    val audioRouteStatus: StateFlow<AudioRouteStatus>
        get() = audio.routeStatus

    override fun onCreate() {
        super.onCreate()
        createdAtMs = System.currentTimeMillis()
        audio = AudioPipeline(this)
        pttWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PTT_WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        session = ChannelViewModel(
            SharedPreferencesConnectionPreferences(this),
            ZenWebSocketClient(networkMonitor = DefaultNetworkMonitor(this)),
            audio,
            healthClient = OkHttpServerHealthClient(),
        )
        headset = HeadsetController(this, scope, ::hardwarePttDown, ::hardwarePttUp)
        audio.setHeadsetEnabled(session.state.value.headsetSettings.enabled)
        audio.onInputRouteLost = {
            headset.reset("audio_device_disconnected")
            hardwarePttUp()
            session.inputRouteLost()
        }
        createActivePttNotificationChannel(notifications)
        scope.launch {
            session.state.collect { state ->
                refreshHardware()
                if (pttSetup.value == null && headset.engaged && (
                        !session.isPttHeld() || state.pttError != null ||
                            state.pttNotice?.kind == PttNoticeKind.Rejected
                    )) {
                    headset.reset("transmission_finished")
                }
                if (state.currentChannel == null || state.pttError != null) {
                    releasePttWakeLock()
                }
            }
        }
        scope.launch {
            session.state.distinctUntilChangedBy { Triple(it.currentChannel, it.status, it.pttError) }
                .collect { state ->
                    if (foregroundStarted && state.currentChannel != null) {
                        publishActivePttNotification(this@PttForegroundService, notifications, state)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCommandCount += 1
        return when (intent?.action) {
            ACTION_CONNECT -> startSession(intent)
            ACTION_DISCONNECT -> {
                stopSession()
                START_NOT_STICKY
            }
            else -> {
                if (session.state.value.currentChannel == null) stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        headset.close()
        pttInputs.clear()
        releasePttWakeLock()
        session.close()
        scope.cancel()
        super.onDestroy()
    }

    fun setChannelCode(value: String) = session.setChannelCode(value)
    fun setHeadsetSettings(value: HeadsetSettings) {
        cancelPttSetup()
        val selected = if (value.valid) value else value.copy(enabled = false)
        if (selected == state.value.headsetSettings && !state.value.headsetChangePending) return
        requestHeadsetSettings(selected, saving = false)
    }

    private fun requestHeadsetSettings(value: HeadsetSettings, saving: Boolean) {
        headsetChange?.cancel()
        savingHeadset = saving
        session.headsetChangePending(true)
        if (saving) headset.saving()
        headsetChange = audio.atRouteBoundary(
            canApply = { !session.isPttHeld() && !state.value.pendingPtt && state.value.status !in setOf(
                SessionStatus.Requesting, SessionStatus.Transmitting, SessionStatus.Releasing,
            ) },
            apply = {
                val saved = session.setHeadsetSettings(value)
                if (saved) {
                    audio.setHeadsetEnabled(value.enabled)
                    refreshHardware()
                    if (saving) { headset.cancelSetup(); session.headsetSetupSaved() }
                } else if (saving) headset.saveFailed()
                savingHeadset = false
                session.headsetChangePending(false)
                saved
            },
        )
    }

    fun beginPttSetup(useProtocolHints: Boolean = true) {
        if (state.value.headsetChangePending || session.isPttHeld() || state.value.pendingPtt || state.value.status in setOf(
                SessionStatus.Requesting, SessionStatus.Transmitting, SessionStatus.Releasing,
            )) return
        hardwarePttUp()
        headset.startSetup(useProtocolHints)
    }

    fun cancelPttSetup() {
        if (savingHeadset) {
            headsetChange?.cancel()
            savingHeadset = false
            session.headsetChangePending(false)
        }
        headset.cancelSetup()
    }

    fun nextSetupStep() = headset.next()
    fun selectHeadsetDevice(id: String?) = headset.selectDevice(id)
    fun retryPttSetup() = headset.retry()
    fun choosePttBehavior(behavior: ButtonBehavior) = headset.choose(behavior)
    fun confirmHeadsetTest(value: Boolean) = headset.confirm(value)
    fun savePttSetup() {
        val setup = headset.result() ?: return
        if (state.value.headsetChangePending) return
        requestHeadsetSettings(HeadsetSettings(enabled = true, setup = setup), saving = true)
    }

    private fun refreshHardware() {
        val snapshot = state.value
        val settings = snapshot.headsetSettings
        if (hardwareSettings != settings || hardwareChannel != snapshot.currentChannel) {
            headset.reset("session_or_settings_changed")
            hardwarePttUp()
            hardwareSettings = settings
            hardwareChannel = snapshot.currentChannel
        }
        headset.configure(settings, snapshot.currentChannel != null)
    }
    fun playPttRejected(message: String = "PTT unavailable — release and press PTT again.") =
        session.playPttRejected(message)
    fun applyChannelCode(value: String): Boolean = session.applyChannelCode(value)
    fun applySettings(
        serverAddress: String,
        powerSaveTimeoutMinutes: String,
    ): Boolean = session.applySettings(
        serverAddress,
        powerSaveTimeoutMinutes,
    )
    fun prepareConnection(echo: Boolean): Boolean = session.prepareConnection(echo)
    fun reportError(target: UiErrorTarget, message: String) = session.reportError(target, message)
    fun consumeUiError(id: Int) = session.consumeUiError(id)
    fun consumePttNotice(id: Int) = session.consumePttNotice(id)
    fun invalidateServerCheck() = session.invalidateServerCheck()
    fun reconnect() = session.reconnect()
    fun pttDown() { if (pttSetup.value == null) pttInputDown(PttInputSource.Touch) }
    fun pttUp() = pttInputUp(PttInputSource.Touch)
    internal suspend fun awaitAudioCleanup() = audio.awaitCleanup()
    fun accessibilityPttDown() { if (pttSetup.value == null) pttInputDown(PttInputSource.Accessibility) }
    fun accessibilityPttUp() = pttInputUp(PttInputSource.Accessibility)

    private fun hardwarePttDown() = pttInputDown(PttInputSource.Hardware)
    private fun hardwarePttUp() = pttInputUp(PttInputSource.Hardware)

    private fun pttInputDown(source: PttInputSource) {
        if (!state.value.headsetChangePending) pttInputs.down(source)
    }

    private fun pttInputUp(source: PttInputSource) = pttInputs.up(source)

    private fun startPtt() {
        val startedSessionForPress = state.value.currentChannel == null
        if (startedSessionForPress && !startSessionForPtt()) return
        if (session.pttDown()) {
            // Settings may publish a disconnected snapshot before the session starts.
            acquirePttWakeLock()
            return
        }
        releasePttWakeLock()
        if (startedSessionForPress && state.value.currentChannel == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun stopPtt() {
        try {
            session.pttUp()
        } finally {
            releasePttWakeLock()
        }
    }

    fun pingServer(address: String) = session.pingServer(address)
    fun diagnosticCodeCopied() = session.diagnosticCodeCopied()
    fun uploadDiagnosticReport(report: DiagnosticReport) {
        if (state.value.diagnosticUploading) return
        val generation = session.diagnosticUploadStarted()
        diagnosticUploadClient.upload(state.value.serverAddress, report) { result ->
            session.diagnosticUploadFinished(generation, result)
        }
    }
    fun permissionChanged() = headset.permissionChanged()
    fun handleKeyEvent(event: KeyEvent): Boolean = headset.handleKeyEvent(event)

    fun debugReport(appVersion: String, androidVersion: String): String =
        session.debugReport(appVersion, androidVersion) +
            "\nservice.created_at_ms=$createdAtMs" +
            "\nservice.start_commands=$startCommandCount" +
            "\nservice.foreground=$foregroundStarted" +
            "\nservice.headset_change_pending=${state.value.headsetChangePending}" +
            "\nservice.battery_optimization_exempt=${isBatteryOptimizationExempt()}" +
            "\nuser.network=${networkIndicator(state.value.status)}" +
            "\nservice.ptt_wake_lock=" +
            "held=${isPttWakeLockHeld()},acquires=${pttWakeLockAcquisitions.get()}," +
            "failures=${pttWakeLockFailures.get()}" +
            "\nuser.headset=${headsetIndicator(hardwareStatus.value)}" +
            "\nuser.audio_route=${audioRouteStatus.value.label}" +
            "\n\n" + headset.debugReport()

    private fun startSession(intent: Intent): Int {
        if (!startForegroundSafely()) return START_NOT_STICKY
        if (session.state.value.currentChannel != null) {
            // A repeated start must not leave the temporary Connecting notification behind.
            publishActivePttNotification(this, notifications, session.state.value)
            return START_REDELIVER_INTENT
        }

        session.setServerAddress(intent.getStringExtra(EXTRA_SERVER_ADDRESS).orEmpty())
        session.setChannelCode(intent.getStringExtra(EXTRA_CHANNEL_CODE).orEmpty())
        session.setPowerSaveTimeoutMinutes(intent.getStringExtra(EXTRA_POWER_SAVE_TIMEOUT).orEmpty())
        session.applySettings(
            session.state.value.serverAddress,
            session.state.value.powerSaveTimeoutMinutes,
        )
        session.connect(echo = intent.getBooleanExtra(EXTRA_ECHO, false))

        if (session.state.value.currentChannel == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return START_NOT_STICKY
        }
        refreshHardware()
        publishActivePttNotification(this, notifications, session.state.value)
        return START_REDELIVER_INTENT
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            startForeground(
                ACTIVE_PTT_NOTIFICATION_ID,
                activePttNotification(this, session.state.value, connecting = session.state.value.currentChannel == null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foregroundStarted = true
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "foreground_service_start_failed", error)
            session.reportError(UiErrorTarget.General, "Unable to start background mode. Check app permissions.")
            stopSelf()
            false
        }
    }

    private fun startSessionForPtt(): Boolean {
        if (!session.prepareConnection(echo = false)) {
            session.playPttRejected()
            return false
        }
        if (!startForegroundSafely()) {
            session.playPttRejected()
            return false
        }
        return try {
            connect(this, state.value, echo = false)
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "foreground_service_start_request_failed", error)
            session.reportError(
                UiErrorTarget.General,
                "Unable to start background mode. Try again while the app is open.",
            )
            session.playPttRejected()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            false
        }
    }

    private fun stopSession() {
        headset.configure(state.value.headsetSettings, false)
        headset.cancelSetup()
        session.disconnect()
        pttInputs.clear()
        releasePttWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun isBatteryOptimizationExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun acquirePttWakeLock() {
        try {
            pttWakeLock.acquire(PTT_WAKE_LOCK_TIMEOUT_MS)
            pttWakeLockAcquisitions.incrementAndGet()
        } catch (error: RuntimeException) {
            pttWakeLockFailures.incrementAndGet()
            Log.e(TAG, "ptt_wake_lock_acquire_failed", error)
        }
    }

    private fun releasePttWakeLock() {
        if (!isPttWakeLockHeld()) return
        try {
            pttWakeLock.release()
        } catch (error: RuntimeException) {
            pttWakeLockFailures.incrementAndGet()
            Log.e(TAG, "ptt_wake_lock_release_failed", error)
        }
    }

    private fun isPttWakeLockHeld(): Boolean =
        ::pttWakeLock.isInitialized && pttWakeLock.isHeld

    companion object {
        private const val TAG = "ZenPTT.Service"

        private const val PTT_WAKE_LOCK_TAG = "ZenPTT:ptt"
        private const val PTT_WAKE_LOCK_TIMEOUT_MS = 65_000L

        private const val ACTION_CONNECT = "app.zenptt.action.CONNECT"
        private const val EXTRA_SERVER_ADDRESS = "server_address"
        private const val EXTRA_CHANNEL_CODE = "channel_code"
        private const val EXTRA_POWER_SAVE_TIMEOUT = "power_save_timeout"
        private const val EXTRA_ECHO = "echo"

        fun connect(context: Context, state: ChannelUiState, echo: Boolean) {
            context.startForegroundService(
                Intent(context, PttForegroundService::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra(EXTRA_SERVER_ADDRESS, state.serverAddress)
                    putExtra(EXTRA_CHANNEL_CODE, state.channelCode)
                    putExtra(EXTRA_POWER_SAVE_TIMEOUT, state.powerSaveTimeoutMinutes)
                    putExtra(EXTRA_ECHO, echo)
                },
            )
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, PttForegroundService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }
}
