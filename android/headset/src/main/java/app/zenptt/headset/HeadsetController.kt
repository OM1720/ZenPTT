// Runs headset setup, learning, verification, and the active hardware PTT source.
package app.zenptt.headset

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SetupStep { Prepare, Devices, Connecting, Quiet, Hold, Release, Switching, OtherButtons, Behavior, Test, Error }
enum class SetupCommand { Connect, Select, Wait, KeepReleased, Hold, Release, Press, PressAgain, OtherButtons, ChooseMode, CheckIndicator, Continue, Retry }

data class HeadsetSetupState(
    val step: SetupStep = SetupStep.Prepare,
    val source: HeadsetSource? = null,
    val devices: List<HeadsetDevice> = emptyList(),
    val round: Int = 0,
    val holdAvailable: Boolean = false,
    val behavior: ButtonBehavior = ButtonBehavior.Toggle,
    val testOn: Boolean = false,
    val testComplete: Boolean = false,
    val confirmed: Boolean = false,
    val canNext: Boolean = false,
    val hid: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val command: SetupCommand get() = if (saving) SetupCommand.Wait else when (step) {
        SetupStep.Prepare -> SetupCommand.Connect
        SetupStep.Devices -> if (devices.isEmpty()) SetupCommand.Connect else SetupCommand.Select
        SetupStep.Connecting -> SetupCommand.Wait
        SetupStep.Switching -> SetupCommand.Continue
        SetupStep.Error -> SetupCommand.Retry
        SetupStep.Quiet -> SetupCommand.KeepReleased
        SetupStep.Hold -> SetupCommand.Hold
        SetupStep.Release -> SetupCommand.Release
        SetupStep.OtherButtons -> SetupCommand.OtherButtons
        SetupStep.Behavior -> SetupCommand.ChooseMode
        SetupStep.Test -> when {
            testComplete && !testOn -> SetupCommand.CheckIndicator
            behavior == ButtonBehavior.Hold -> if (testOn) SetupCommand.Release else SetupCommand.Hold
            testOn -> SetupCommand.PressAgain
            else -> SetupCommand.Press
        }
    }
}

// One service-owned controller serializes all input on the main dispatcher.
class HeadsetController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPressed: () -> Unit,
    private val onReleased: () -> Unit,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val connectedDevices: (() -> List<HeadsetDevice>)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val _setup = MutableStateFlow<HeadsetSetupState?>(null)
    val setup = _setup.asStateFlow()
    private val _status = MutableStateFlow("disabled")
    val status = _status.asStateFlow()
    private var settings = HeadsetSettings()
    private var sessionActive = false
    private var closed = false
    private var connection: SourceConnection? = null
    private var epoch = 0L
    private var mediaSession: MediaSession? = null
    private var receiverRegistered = false
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectTimeout: Job? = null
    private var retryCount = 0
    private var input = ButtonInput()
    private var keyCutoff = 0L
    private var template: HeadsetSetup? = null
    private var learner: ButtonLearner? = null
    private var hints = true
    private var search: SetupSearch? = null
    private val rounds = mutableListOf<TrainingRound>()
    private val bleServices = mutableMapOf<String, String>()
    private var candidate: LearnedCandidate? = null
    private var phaseAt = 0L
    private var pressAt = 0L
    private var releaseAt = 0L
    private var sourceAt = 0L
    private var probeReady = false
    private var sawEvent = false
    private var testStarted = false
    private var anyInconsistent = false
    private val events = ArrayDeque<String>()
    val engaged: Boolean get() = input.engaged
    private val discovery = HeadsetDiscovery(context, { connection?.connectedDevice }) { devices ->
        main.post { if (_setup.value?.step == SetupStep.Devices) _setup.value = _setup.value?.copy(devices = devices) }
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val adapterChanged = intent.action == BluetoothAdapter.ACTION_STATE_CHANGED
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val target = template ?: settings.setup
            val matches = device != null && (device.address == target.device || (target.autoSelect && runCatching {
                device.name?.let { it.contains("BM008", true) || it.contains("PTT", true) } == true
            }.getOrDefault(false)))
            if (!adapterChanged && !matches && target.source != HeadsetSource.Media) return
            val disconnected = intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED && intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED) ||
                (adapterChanged && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON)
            if (disconnected) {
                // ACL/profile broadcasts have no connection generation. Closing a BLE probe can
                // deliver a late disconnect after SPP is ready on the same physical headset.
                // Owned transports report their own failures through generation-checked callbacks.
                val awaitSource = !adapterChanged && target.source in setOf(HeadsetSource.Spp, HeadsetSource.Ble)
                record("bluetooth_disconnect action=${intent.action} source=${target.source} ready=$probeReady decision=${if (awaitSource) "await_source" else "reset"}")
                if (awaitSource) return
                reset("device_disconnected")
                if (_setup.value != null) fail("Headset disconnected. Your previous setup is unchanged.")
                else if (target.source in setOf(HeadsetSource.Spp, HeadsetSource.Ble)) runtimeFailure("device_disconnected")
            } else if (_setup.value == null && settings.enabled && sessionActive && !probeReady) {
                openRuntime()
            }
        }
    }

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = onInputDeviceRemoved(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) {
            val target = template ?: settings.setup
            if (target.source == HeadsetSource.Hid && !hidConnected(target.device)) {
                reset("input_device_disconnected")
                if (_setup.value != null) fail("Headset disconnected. Your previous setup is unchanged.")
            }
        }
    }

    fun configure(value: HeadsetSettings, activeSession: Boolean) {
        if (closed || (value == settings && activeSession == sessionActive)) return
        reset("configuration_changed")
        settings = value
        sessionActive = activeSession
        if (_setup.value == null) { input = ButtonInput(); keyCutoff = now(); openRuntime() }
        resources()
    }

    fun startSetup(useProtocolHints: Boolean = true) {
        if (closed) return
        reset("setup_started")
        if (!probeReady) stopConnection()
        hints = useProtocolHints
        search = null
        candidate = null
        anyInconsistent = false
        keyCutoff = now()
        _setup.value = HeadsetSetupState()
        _status.value = "configuring"
        record("setup_started hints=$hints")
        resources()
        tickJob?.cancel()
        tickJob = scope.launch { while (isActive) { delay(100); tick() } }
    }

    fun next() {
        when (_setup.value?.step) {
            SetupStep.Prepare -> {
                update(SetupStep.Devices)
                phaseAt = now()
                if (connectedDevices == null) discovery.start()
                else _setup.value = _setup.value?.copy(devices = connectedDevices.invoke())
            }
            SetupStep.Switching -> startNextSource()
            SetupStep.OtherButtons -> if (_setup.value?.canNext == true) update(SetupStep.Behavior)
            SetupStep.Behavior -> {
                input = ButtonInput()
                testStarted = false
                phaseAt = now()
                keyCutoff = now()
                update(SetupStep.Test)
            }
            else -> Unit
        }
    }

    fun selectDevice(id: String?) {
        if (_setup.value?.step != SetupStep.Devices) return
        if (id == null) return
        val device = if (connectedDevices == null) discovery.findConnected(id)
            else connectedDevices.invoke().firstOrNull { it.id == id }
        if (device == null) return
        discovery.stop()
        candidate = null
        search = SetupSearch(device)
        startNextSource()
    }

    fun retry() { startSetup(hints); next() }
    fun choose(behavior: ButtonBehavior) {
        if (_setup.value?.step == SetupStep.Behavior && (behavior == ButtonBehavior.Toggle || candidate?.hold == true)) {
            candidate = candidate?.let { it.copy(setup = it.setup.copy(behavior = behavior)) }
            _setup.value = _setup.value?.copy(behavior = behavior)
        }
    }
    fun confirm(value: Boolean) {
        if (_setup.value?.step == SetupStep.Test) _setup.value = _setup.value?.copy(confirmed = value)
    }
    fun result(): HeadsetSetup? = candidate?.setup?.takeIf {
        val state = _setup.value
        state?.step == SetupStep.Test && state.testComplete && !state.testOn && state.confirmed && it.isValid()
    }
    fun saving() { _setup.value = _setup.value?.copy(saving = true, error = null) }
    fun saveFailed() { _setup.value = _setup.value?.copy(saving = false, error = "Could not save the setup. Your previous setup is unchanged.") }

    fun cancelSetup() {
        if (_setup.value == null) return
        stopConnection()
        discovery.stop()
        tickJob?.cancel()
        tickJob = null
        learner = null
        search = null
        candidate = null
        _setup.value = null
        input = ButtonInput()
        keyCutoff = now()
        record("setup_closed")
        openRuntime()
        resources()
    }

    private fun startNextSource() {
        stopConnection()
        val next = search?.next() ?: run { finishSearch(); return }
        candidate = null
        learner = ButtonLearner(next)
        rounds.clear()
        bleServices.clear()
        sawEvent = false
        sourceAt = now()
        _setup.value = _setup.value?.copy(source = next.source)
        update(SetupStep.Connecting)
        record("source_probe source=${next.source}")
        open(next) { sourceAt = now(); phaseAt = sourceAt; update(SetupStep.Quiet) }
    }

    private fun completeSource() {
        val learned = learner?.learn(rounds).orEmpty().map { item ->
            if (item.setup.source == HeadsetSource.Ble) item.copy(setup = item.setup.copy(service = bleServices[item.setup.characteristic].orEmpty())) else item
        }.filter { it.setup.isValid() }
        if (learned.isEmpty() && sawEvent) anyInconsistent = true
        candidate = search?.accept(learned)
        record("source_result source=${template?.source} accepted=${learned.size} overflow=${learner?.overflow}")
        learner = null
        stopConnection()
        if (candidate != null || search?.nextSource == null) finishSearch() else switchSource()
    }

    private fun finishSearch() {
        val best = candidate ?: run {
            fail(if (anyInconsistent) INCONSISTENT else NOT_DETECTED)
            return
        }
        learner = null
        input = ButtonInput()
        _setup.value = HeadsetSetupState(step = SetupStep.Connecting, source = best.setup.source, holdAvailable = best.hold,
            behavior = best.setup.behavior, hid = best.setup.source == HeadsetSource.Hid)
        record("hypothesis_selected source=${best.setup.source} rule=${best.setup.rule.kind} hold=${best.hold}")
        open(best.setup) { phaseAt = now(); update(SetupStep.OtherButtons) }
    }

    private fun switchSource() {
        _setup.value = _setup.value?.copy(source = search?.nextSource)
        update(SetupStep.Switching)
    }

    private fun tick() {
        val state = _setup.value ?: return
        if (state.saving) return
        val time = now()
        if (learner != null && (time - sourceAt > ButtonLearner.MAX_SOURCE_MS || learner?.overflow == true)) {
            record("source_limit")
            completeSource()
            return
        }
        when (state.step) {
            SetupStep.Quiet -> if (time - phaseAt >= ButtonLearner.QUIET_MS) {
                pressAt = time
                _setup.value = state.copy(step = SetupStep.Hold, round = rounds.size + 1)
            }
            SetupStep.Hold -> if (time - pressAt >= ButtonLearner.HOLD_MS[rounds.size]) {
                releaseAt = time
                update(SetupStep.Release)
            }
            SetupStep.Release -> if (time - releaseAt >= ButtonLearner.QUIET_MS) {
                rounds += TrainingRound(pressAt, releaseAt, time)
                if (rounds.size == 5 || !sawEvent) completeSource() else {
                    pressAt = time
                    _setup.value = state.copy(step = SetupStep.Hold, round = rounds.size + 1)
                }
            }
            SetupStep.OtherButtons -> if (!state.canNext && time - phaseAt >= ButtonLearner.QUIET_MS) _setup.value = state.copy(canNext = true)
            SetupStep.Test -> if (!state.testComplete && time - phaseAt > CONNECT_TIMEOUT_MS) fail(NOT_DETECTED)
            else -> Unit
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (closed || event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val target = template ?: return false
        val learning = _setup.value != null
        if (!learning && (!settings.enabled || !settings.valid || !sessionActive)) return false
        if (target.source in setOf(HeadsetSource.Spp, HeadsetSource.Ble)) return event.keyCode in MEDIA_KEYS
        val supported = when (target.source) {
            HeadsetSource.Media -> event.keyCode in MEDIA_KEYS
            HeadsetSource.Hid -> event.device?.let { it.isExternal && !it.isVirtual && it.descriptor == target.device } == true &&
                !event.isSystem && !KeyEvent.isModifierKey(event.keyCode) && event.keyCode !in VOLUME_KEYS
            else -> false
        }
        if (!supported || (!learning && event.keyCode != target.rule.keyCode)) return false
        if (event.isCanceled) { reset("key_canceled"); if (learning) fail(INCONSISTENT); return true }
        if (event.repeatCount != 0 || event.downTime <= keyCutoff) return true
        observe(Observation(event.eventTime, "key:${event.keyCode}", keyCode = event.keyCode,
            pressed = event.action == KeyEvent.ACTION_DOWN, pressId = event.downTime))
        return true
    }

    private fun observe(event: Observation) {
        if (!probeReady) return
        val target = template ?: return
        record("event source=${target.source} bytes=${event.value.length / 2} key=${event.keyCode} pressed=${event.pressed}")
        val state = _setup.value
        if (state?.saving == true) return
        if (state != null && learner != null) {
            sawEvent = true
            learner?.add(event)
            return
        }
        val rule = candidate?.setup?.rule ?: target.rule
        if (target.source == HeadsetSource.Ble && event.endpoint != target.characteristic) return
        if (target.source == HeadsetSource.Spp && event.endpoint != if (target.framing == Framing.KnownTokens) TOKEN_ENDPOINT else LINE_ENDPOINT) return
        if (state?.step == SetupStep.OtherButtons && rule.match(event) in setOf(Edge.Down, Edge.Pulse)) {
            fail(INCONSISTENT)
            return
        }
        if (state != null && state.step != SetupStep.Test) return
        val on = input.event(event, rule, candidate?.setup?.behavior ?: target.behavior) ?: return
        if (state != null) {
            phaseAt = now()
            if (on) testStarted = true
            _setup.value = state.copy(testOn = on, testComplete = state.testComplete || (testStarted && !on), confirmed = false)
        } else {
            record("ptt ${if (on) "pressed" else "released"}")
            if (on) onPressed() else onReleased()
        }
    }

    private fun open(target: HeadsetSetup, ready: () -> Unit) {
        stopConnection()
        template = target
        val generation = epoch
        fun deliver(block: () -> Unit) { main.post { if (!closed && generation == epoch) block() } }
        val readyCallback = { deliver { connectTimeout?.cancel(); probeReady = true; retryCount = 0; ready() } }
        val failedCallback: (String) -> Unit = { reason -> deliver { connectionFailed(reason) } }
        val eventCallback: (String, ByteArray) -> Unit = { endpoint, bytes ->
            val timestamp = now()
            if (bytes.size > MAX_MESSAGE_BYTES) deliver { connectionFailed("message_limit") }
            else deliver { observe(Observation(timestamp, endpoint, value = bytes.hex())) }
        }
        connectTimeout = scope.launch { delay(CONNECT_TIMEOUT_MS); if (generation == epoch) connectionFailed("connect_timeout") }
        when (target.source) {
            HeadsetSource.Media, HeadsetSource.Hid -> readyCallback()
            HeadsetSource.Spp -> connection = SppConnection(context, scope, target,
                if (_setup.value != null && learner != null) hints else target.framing == Framing.KnownTokens,
                if (learner != null) ButtonLearner.MAX_BYTES else Int.MAX_VALUE,
                readyCallback, eventCallback, failedCallback)
            HeadsetSource.Ble -> connection = BleConnection(context, target, readyCallback, eventCallback, failedCallback) { characteristic, service ->
                deliver { bleServices[characteristic] = service }
            }
        }
    }

    private fun connectionFailed(reason: String) {
        record("connection_failed reason=$reason")
        if (_setup.value == null) runtimeFailure(reason)
        else if (learner != null) {
            learner = null
            stopConnection()
            if (search?.nextSource == null) finishSearch() else switchSource()
        } else fail("Headset disconnected. Your previous setup is unchanged.")
    }

    private fun openRuntime() {
        stopConnection()
        if (closed || !settings.enabled || !settings.valid || !sessionActive) {
            _status.value = if (!settings.valid) "invalid saved setup" else if (!settings.enabled) "disabled" else "inactive"
            template = null
            return
        }
        _status.value = "connecting"
        open(settings.setup) { _status.value = "ready"; record("connected source=${settings.setup.source}") }
    }

    private fun runtimeFailure(reason: String) {
        reset(reason)
        stopConnection()
        _status.value = "disconnected"
        if (!closed && settings.enabled && sessionActive) {
            val delayMs = listOf(1_000L, 3_000L, 7_000L, 10_000L)[retryCount.coerceAtMost(3)]
            retryCount++
            reconnectJob = scope.launch { delay(delayMs); openRuntime() }
        }
    }

    fun reset(reason: String) {
        if (input.reset() && _setup.value == null) onReleased()
        record("reset reason=$reason")
    }
    fun permissionChanged() { if (_setup.value == null) openRuntime() else if (_setup.value?.step == SetupStep.Devices) discovery.start() }

    private fun fail(message: String) {
        stopConnection()
        learner = null
        input.reset()
        _setup.value = _setup.value?.copy(step = SetupStep.Error, error = message, testOn = false, confirmed = false)
        record("setup_failed $message")
    }
    private fun update(step: SetupStep) { _setup.value = _setup.value?.copy(step = step, error = null); record("setup_step=$step") }
    private fun stopConnection() {
        epoch++
        probeReady = false
        connectTimeout?.cancel()
        reconnectJob?.cancel()
        connection?.close()
        connection = null
    }

    private fun resources() {
        val needed = !closed && (_setup.value != null || (settings.enabled && settings.valid && sessionActive))
        if (needed && mediaSession == null) {
            record("media_session created")
            mediaSession = MediaSession(context, "ZenPTT Headset").apply {
                setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
                setCallback(object : MediaSession.Callback() {
                    @Suppress("DEPRECATION")
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean =
                        mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)?.let(::handleKeyEvent) ?: false
                }, main)
                isActive = true
            }
        } else if (!needed && mediaSession != null) { mediaSession?.release(); mediaSession = null; record("media_session released") }
        if (needed && !receiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }, ContextCompat.RECEIVER_EXPORTED)
                context.getSystemService(InputManager::class.java).registerInputDeviceListener(inputListener, main)
                receiverRegistered = true
                record("bluetooth_receiver registered")
            }.onFailure { record("receiver_failed ${it.javaClass.simpleName}") }
        } else if (!needed && receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            context.getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener)
            receiverRegistered = false
            record("bluetooth_receiver unregistered")
        }
    }

    fun close() {
        reset("service_stopped")
        closed = true
        stopConnection()
        discovery.stop()
        tickJob?.cancel()
        _setup.value = null
        resources()
    }

    fun debugReport(): String = buildString {
        appendLine("headset.enabled=${settings.enabled}")
        appendLine("headset.valid=${settings.valid}")
        appendLine("headset.session_active=$sessionActive")
        appendLine("headset.active=${settings.enabled && settings.valid && sessionActive && _setup.value == null}")
        appendLine("headset.source=${settings.setup.source}")
        appendLine("headset.rule=${settings.setup.rule.kind}")
        appendLine("headset.behavior=${settings.setup.behavior}")
        appendLine("headset.status=${status.value}")
        appendLine("headset.media_session=${mediaSession != null}")
        appendLine("headset.receiver_registered=$receiverRegistered")
        appendLine("headset.setup=${setup.value?.step}")
        events.forEach(::appendLine)
    }
    private fun record(message: String) {
        if (events.size >= 100) events.removeFirst()
        events += "${System.currentTimeMillis()} $message"
        runCatching { Log.d("ZenPTT.Headset", message) }
    }
    private fun hidConnected(descriptor: String) = android.view.InputDevice.getDeviceIds().asIterable()
        .mapNotNull(android.view.InputDevice::getDevice).any { it.descriptor == descriptor }
    private fun now() = clock()
    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val INCONSISTENT = "Button events were inconsistent. Your previous setup is unchanged."
        const val NOT_DETECTED = "Could not detect the PTT button. Your previous setup is unchanged."
        val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
    }
}
