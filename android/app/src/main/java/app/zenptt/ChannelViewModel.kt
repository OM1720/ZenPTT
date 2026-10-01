// Coordinates transport, PTT, audio recovery, and runtime diagnostics in shared UI state.
package app.zenptt

import app.zenptt.headset.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

enum class SessionStatus(val label: String) {
    Connecting("Connecting"),
    Ready("Ready"),
    Requesting("Requesting transmission"),
    Transmitting("Transmitting"),
    Releasing("Finishing transmission"),
    Receiving("Receiving"),
    Busy("Channel busy"),
    Reconnecting("Reconnecting"),
    ConnectionError("Connection error"),
    PlayingEcho("Playing echo"),
}

enum class AudioPathQuality { Good, Fair, Poor }

data class LinkMetrics(
    val rttMs: Long? = null,
    val pttGrantMs: Long? = null,
    val recentSequenceGaps: Long? = null,
)

enum class UiErrorTarget { Frequency, Server, PowerSave, General }

data class UiError(
    val id: Int,
    val target: UiErrorTarget,
    val message: String,
)

enum class PttNoticeKind { Queued, Rejected }

data class PttNotice(
    val id: Int,
    val kind: PttNoticeKind,
    val message: String,
)

data class ChannelUiState(
    val serverAddress: String = "",
    val channelCode: String = "",
    val frequencyChoices: List<String> = listOf(ECHO_CHANNEL),
    val powerSaveTimeoutMinutes: String = DEFAULT_POWER_SAVE_TIMEOUT_MINUTES.toString(),
    val headsetSettings: HeadsetSettings = HeadsetSettings(),
    val headsetChangePending: Boolean = false,
    val headsetSetupSaved: Int = 0,
    val currentChannel: String? = null,
    val participantCount: Int? = null,
    val status: SessionStatus = SessionStatus.Connecting,
    val audioPathQuality: AudioPathQuality = AudioPathQuality.Good,
    val playbackActive: Boolean = false,
    val linkMetrics: LinkMetrics = LinkMetrics(),
    val uiError: UiError? = null,
    val diagnostics: String = "",
    val serverCheckStatus: String? = null,
    val connectionDetail: String? = null,
    val pttError: String? = null,
    val pendingPtt: Boolean = false,
    val pttNotice: PttNotice? = null,
    val playbackInterruptible: Boolean = false,
    val diagnosticUploadStatus: String? = null,
    val diagnosticReportCode: String? = null,
    val diagnosticCopyStatus: String? = null,
    val diagnosticUploading: Boolean = false,
)

interface AudioGate {
    fun start(sender: (ByteArray) -> Boolean)
    fun start(sender: (ByteArray) -> Boolean, onFailure: (AudioCaptureFailure) -> Unit) = start(sender)
    fun grantCapture() = Unit
    fun stopCapture(onStopped: () -> Unit = {}) = stop(onStopped)
    fun stop(onStopped: () -> Unit = {})
    fun play(burstId: String, message: ByteArray): Boolean
    fun debugReport(): String = ""
    fun setSessionActive(active: Boolean) = Unit
    fun setPowerSaveTimeoutMinutes(minutes: Int) = Unit
    fun setAudioBacklogMs(milliseconds: Int) = Unit
    fun playIndicator(indicator: AudioIndicator, onComplete: () -> Unit = {}) = onComplete()
    fun startQueuedCue() = playIndicator(AudioIndicator.PttQueued)
    fun stopQueuedCue() = Unit
    fun incomingTransmissionStarted() = Unit
    fun incomingTransmissionStartedAfterIndicator(indicator: AudioIndicator) {
        playIndicator(indicator) { incomingTransmissionStarted() }
    }
    fun incomingTransmissionEnded(onPlaybackDrained: () -> Unit = {}) = onPlaybackDrained()
    fun playLoss(burstId: String, frameCount: Int): Boolean = true
    fun playbackQueuedFrames(): Int = 0
    fun playbackBacklogEmpty(): Boolean = playbackQueuedFrames() == 0
    fun interruptIncomingPlayback() = Unit
    fun onPttActivity(): Boolean = false
    fun onPttReleased() = Unit
    fun onIncomingAudioActivity() = Unit
    fun close() = stop()
}

object NoOpAudioGate : AudioGate {
    override fun start(sender: (ByteArray) -> Boolean) = Unit
    override fun stop(onStopped: () -> Unit) = onStopped()
    override fun play(burstId: String, message: ByteArray) = true
}

class ChannelViewModel(
    addressStore: ConnectionPreferences,
    private val connection: ConnectionClient,
    private val audio: AudioGate = NoOpAudioGate,
    private val ptt: PttSession = PttSession(),
    private val diagnostics: Diagnostics = Diagnostics(),
    healthClient: ServerHealthClient = NoOpServerHealthClient,
    private val nowMs: () -> Long = diagnostics::monotonicNowMs,
) : ConnectionListener {
    private val _state = MutableStateFlow(addressStore.loadInitialChannelState())
    private val pttNoticeGeneration = AtomicInteger()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pttTransitionLock = Any()
    private var pttRequestDeadline: PttRequestDeadline? = null
    private var pttQueueDeadline: PttQueueDeadline? = null
    private var pttQueueDeadlineJob: Job? = null
    private var connectionPhase: ConnectionPhase = ConnectionPhase.Stopped
    private val outgoingAudio = OutgoingAudioStore()
    private val incomingAudio = IncomingAudioStore()
    private var playbackPhase: PlaybackPhase = PlaybackPhase.Idle
    private var nextPlaybackToken = 0L
    private var playbackPumpJob: Job? = null
    private var serverEpoch: ServerEpoch? = null
    private var resumePhase: ResumePhase = ResumePhase.None
    private var ackWatchdog = AckWatchdogState()
    private var captureDeadlines: CaptureDeadlines? = null
    private var outgoingQueueBytes = 0L
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    private val socketGeneration: Long
        get() = serverEpoch?.generation ?: 0
    private val audioPolicy: AudioPolicy
        get() = serverEpoch?.audioPolicy ?: AudioPolicy.Default
    private val backfillBeforeBurstIndex: Long?
        get() = serverEpoch?.backfillBeforeBurstIndex

    private val settingsController = ChannelSettingsController(
        _state, addressStore, healthClient, audio::setPowerSaveTimeoutMinutes,
    )

    init {
        runtimeScope.launch {
            while (true) {
                delay(SENDER_SCHEDULER_INTERVAL_MS)
                synchronized(pttTransitionLock) {
                    runSchedulerTick()
                }
            }
        }
    }

    fun setServerAddress(value: String) = settingsController.setServerAddress(value)

    fun setChannelCode(value: String) = settingsController.setChannelCode(value)

    fun setHeadsetSettings(value: HeadsetSettings): Boolean = settingsController.setHeadsetSettings(value)

    fun headsetChangePending(value: Boolean) = settingsController.headsetChangePending(value)
    fun headsetSetupSaved() = settingsController.headsetSetupSaved()
    fun inputRouteLost() = handleAudioCaptureFailure(AudioCaptureFailure.RouteUnavailable)

    fun isPttHeld(): Boolean = synchronized(pttTransitionLock) { ptt.physicalHeld() }

    fun playPttRejected(message: String = PTT_REJECTED_ERROR) {
        showPttNotice(PttNoticeKind.Rejected, message)
        audio.playIndicator(AudioIndicator.PttRejected)
    }

    fun reportError(target: UiErrorTarget, message: String) = settingsController.reportError(target, message)

    fun consumeUiError(id: Int) = settingsController.consumeUiError(id)

    fun consumePttNotice(id: Int) = _state.update {
        if (it.pttNotice?.id == id) it.copy(pttNotice = null) else it
    }

    private fun showPttNotice(kind: PttNoticeKind, message: String) = _state.update {
        it.copy(
            pttNotice = PttNotice(
                id = pttNoticeGeneration.incrementAndGet(),
                kind = kind,
                message = message,
            ),
        )
    }

    private fun clearQueuedPttNotice() = _state.update {
        if (it.pttNotice?.kind == PttNoticeKind.Queued) it.copy(pttNotice = null) else it
    }

    fun invalidateServerCheck() = settingsController.invalidateServerCheck()

    fun setPowerSaveTimeoutMinutes(value: String) = settingsController.setPowerSaveTimeoutMinutes(value)

    fun applyChannelCode(value: String): Boolean {
        val previousChannel = _state.value.currentChannel
        val channel = settingsController.applyChannelCode(value) ?: return false
        if (previousChannel != null && previousChannel != channel) {
            stopForConnectionLoss()
            connection.disconnect()
            startConnection(_state.value.serverAddress, channel, clearPttError = true)
        }
        return true
    }

    fun applySettings(
        serverAddress: String,
        powerSaveTimeoutMinutes: String,
    ): Boolean {
        val previousAddress = _state.value.serverAddress
        val activeChannel = _state.value.currentChannel
        val settings = settingsController.applySettings(serverAddress, powerSaveTimeoutMinutes) ?: return false
        if (activeChannel != null && previousAddress != settings.address) {
            stopForConnectionLoss()
            connection.disconnect()
            startConnection(settings.address, activeChannel, clearPttError = true)
        }
        return true
    }

    fun pingServer(value: String = _state.value.serverAddress) = settingsController.pingServer(value)

    fun diagnosticUploadStarted(): Int = settingsController.diagnosticUploadStarted()

    fun diagnosticUploadFinished(generation: Int, result: Result<String>) =
        settingsController.diagnosticUploadFinished(generation, result)

    fun diagnosticCodeCopied() = settingsController.diagnosticCodeCopied()
    fun prepareConnection(echo: Boolean = false): Boolean =
        settingsController.validatedConnection(echo) != null

    fun connect(echo: Boolean = false) {
        val config = settingsController.validatedConnection(echo) ?: return
        settingsController.persistConnection(config)
        startConnection(config.address, config.channel)
    }

    fun pttDown(): Boolean = synchronized(pttTransitionLock) {
        if (ptt.physicalHeld()) return@synchronized true
        val config = settingsController.validatedConnection(echo = false) ?: run {
            playPttRejected("Fix the connection settings, then press PTT again.")
            return@synchronized false
        }
        settingsController.persistConnection(config)
        if (
            config.channel == ECHO_CHANNEL &&
            _state.value.currentChannel == ECHO_CHANNEL &&
            _state.value.participantCount != 2
        ) {
            playPttRejected(ECHO_UNAVAILABLE_ERROR)
            publishRuntimeState()
            return@synchronized false
        }
        if (_state.value.currentChannel == config.channel && interruptDrainingPlayback()) {
            if (connectionPhase == ConnectionPhase.Active) {
                startPttRequest()
            } else {
                deferPttUntilConnected()
            }
            return@synchronized true
        }
        val snapshot = _state.value
        when {
            snapshot.currentChannel == config.channel &&
                snapshot.status in setOf(SessionStatus.Ready, SessionStatus.Receiving) -> {
                startPttRequest()
                true
            }
            snapshot.currentChannel == config.channel &&
                snapshot.status in setOf(SessionStatus.Connecting, SessionStatus.Reconnecting) -> {
                deferPttUntilConnected()
                true
            }
            snapshot.currentChannel == config.channel &&
                snapshot.status == SessionStatus.ConnectionError -> {
                deferPttUntilConnected()
                startConnection(config.address, config.channel, clearPttError = true)
                true
            }
            snapshot.currentChannel != config.channel -> {
                stopForConnectionLoss()
                if (snapshot.currentChannel != null) connection.disconnect()
                startConnection(config.address, config.channel, clearPttError = true)
                deferPttUntilConnected()
                true
            }
            else -> {
                startPttRequest()
                false
            }
        }
    }

    fun pttUp() = synchronized(pttTransitionLock) {
        if (ptt.pendingPhysicalHold()) {
            cancelPendingPtt()
            return@synchronized
        }
        val wasBusy = ptt.status == SessionStatus.Busy
        val actions = ptt.release()
        if (wasBusy) _state.update { it.copy(pttError = null) }
        stopPttQueue(clearNotice = true)
        publishRuntimeState()
        perform(actions)
        audio.onPttReleased()
    }

    fun reconnect() {
        val channel = _state.value.currentChannel ?: return
        startConnection(_state.value.serverAddress, channel)
    }

    fun disconnect() = synchronized(pttTransitionLock) {
        cancelPendingPtt()
        stopForConnectionLoss(notifyInterruption = false)
        connection.disconnect()
        connectionPhase = ConnectionPhase.Stopped
        _state.update {
            ChannelUiState(
                serverAddress = it.serverAddress,
                channelCode = it.channelCode,
                frequencyChoices = it.frequencyChoices,
                powerSaveTimeoutMinutes = it.powerSaveTimeoutMinutes,
                headsetSettings = it.headsetSettings,
            )
        }
        publishRuntimeState()
    }

    fun debugReport(appVersion: String, androidVersion: String): String = synchronized(pttTransitionLock) {
        val snapshot = _state.value
        val channel = when (snapshot.currentChannel) {
            null -> "none"
            ECHO_CHANNEL -> ECHO_CHANNEL
            else -> "normal (redacted)"
        }
        val oldestUnacknowledgedAgeMs = if (outgoingAudio.frameCount() == 0) {
            0
        } else {
            outgoingAudio.oldestUnacknowledgedAgeMs(nowMs())
        }
        listOf(
            "ZenPTT $appVersion",
            "Android $androidVersion",
            "server=${snapshot.serverAddress}",
            "channel=$channel",
            "status=${snapshot.status.label}",
            "health=${snapshot.serverCheckStatus ?: "not checked"}",
            "connection=${snapshot.connectionDetail ?: "no error reported"}",
            "ptt_error=${snapshot.pttError ?: "none"}",
            "ptt_pending=${snapshot.pendingPtt}",
            "metrics=${snapshot.diagnostics.ifEmpty { "not available" }}",
            "recovery=horizon:${audioPolicy.recoveryHorizonMs}ms," +
                "frames:${audioPolicy.recoveryFrames},history:${audioPolicy.serverHistoryMs}ms," +
                "ack_watchdog:${audioPolicy.ackWatchdogMs}ms,fifo_limit:${audioPolicy.receiveFifoFrames}," +
                "ack_cursor:${outgoingAudio.lastAcknowledgedBurstId?.takeLast(6) ?: "none"}:" +
                "${outgoingAudio.lastAcknowledgedNextSequence}," +
                "oldest_unacked:${oldestUnacknowledgedAgeMs}ms," +
                "retransmitted:${outgoingAudio.retransmittedFrameCount}," +
                "receive_fifo:${incomingAudio.frameCount()}/${incomingAudio.maxFrameCount}",
            "power_save_timeout=${snapshot.powerSaveTimeoutMinutes}m",
        ).joinToString("\n") + "\n" + diagnostics.connectionDebugReport() +
            "\n" + diagnostics.sourceSendDebugReport() +
            "\n" + diagnostics.receivePathDebugReport() +
            "\n" + diagnostics.pttDebugReport() +
            audio.debugReport().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
    }

    override fun onControl(event: ControlEvent): Unit = synchronized(pttTransitionLock) {
        when (event) {
            is ControlEvent.Snapshot -> handleSnapshot(event.session)
            is ControlEvent.ResumeRejected -> {
                resumePhase = when (val phase = resumePhase) {
                    is ResumePhase.AwaitingSnapshot -> phase.copy(resetReliableState = true)
                    is ResumePhase.AwaitingBarriers -> ResumePhase.AwaitingSnapshot(
                        captureStopPending = phase.captureStopPending,
                        resetReliableState = true,
                    )
                    ResumePhase.None -> ResumePhase.AwaitingSnapshot(resetReliableState = true)
                }
                diagnostics.connectionDiagnostic("Resume rejected: ${event.reason.take(80)}")
            }
            is ControlEvent.ChannelState -> {
                val echoBecameUnavailable =
                    _state.value.currentChannel == ECHO_CHANNEL &&
                        _state.value.participantCount == 2 &&
                        event.participantCount < 2
                _state.update { it.copy(participantCount = event.participantCount) }
                if (echoBecameUnavailable) handleEchoUnavailable()
                publishRuntimeState()
            }
            is ControlEvent.PttGranted -> handlePttGranted(event)
            is ControlEvent.PttDenied -> handlePttDenied(event)
            is ControlEvent.UplinkAck -> {
                if (outgoingAudio.acknowledge(event.burstId, event.nextSequence)) {
                    ackWatchdog = AckWatchdogState(
                        generation = socketGeneration,
                        progressAtMs = nowMs(),
                    )
                }
                updateUplinkQuality()
                updateDiagnostics()
            }
            is ControlEvent.AudioRejected -> {
                diagnostics.recoveryRejected(
                    event.burstId,
                    event.firstSequence,
                    event.nextSequence,
                    event.reason,
                )
                outgoingAudio.reject(
                    event.burstId,
                    event.firstSequence,
                    event.nextSequence,
                    event.reason,
                )
                if (event.reason == "payload_mismatch") {
                    failProtocolRecovery(
                        "Server reported conflicting audio payload for burst=${event.burstId.takeLast(6)} " +
                            "range=${event.firstSequence}-${event.nextSequence}",
                    )
                    return@synchronized
                }
                flushSender()
            }
            is ControlEvent.BurstStarted -> {
                val wasEmpty = incomingAudio.isEmpty()
                incomingAudio.start(event.burstId, event.burstIndex)
                val becameNonEmpty = wasEmpty && !incomingAudio.isEmpty()
                if (becameNonEmpty && isEchoChannel(_state.value.currentChannel)) {
                    perform(ptt.echoPlaybackStarted())
                } else if (becameNonEmpty) {
                    ptt.remoteStarted()
                }
                if (!incomingAudio.isEmpty()) startIncomingPlayback()
                publishRuntimeState()
                updateDiagnostics()
            }
            is ControlEvent.BurstReleased -> {
                incomingAudio.released(event.burstId)
                pumpIncoming()
            }
            is ControlEvent.BurstGaps -> {
                diagnostics.recoveryGap(event.burstId, event.ranges)
                incomingAudio.gaps(event.burstId, event.ranges)
                pumpIncoming()
            }
            is ControlEvent.BurstSealed -> {
                incomingAudio.sealed(event.burstId, event.finalNextSequence)
                pumpIncoming()
            }
            is ControlEvent.ListenReset -> {
                val cursor = ListenCursor(event.burstIndex, event.nextSequence)
                diagnostics.connectionDiagnostic(
                    "Listen reset burst=${event.burstIndex} sequence=${event.nextSequence} " +
                        "reason=${event.reason}",
                )
                diagnostics.recoveryListenReset(cursor, event.reason)
                incomingAudio.reset(cursor)
                connection.listen(event.burstIndex, event.nextSequence)
                pumpIncoming()
                updateDiagnostics()
            }
            is ControlEvent.PttEnded -> handlePttEnded(event)
            is ControlEvent.Pong -> {
                diagnostics.pongReceived(event.sentAtMs)
                updateUplinkQuality()
                updateDiagnostics()
            }
            is ControlEvent.Error -> {
                diagnostics.serverError(event.code, event.message)
                _state.update {
                    it.copy(connectionDetail = "Server error (${event.code}): ${event.message}".take(200))
                }
                updateDiagnostics()
                reportError(UiErrorTarget.General, event.message)
            }
        }
    }

    override fun onAudio(message: ByteArray) {
        val callbackAtMs = nowMs()
        synchronized(pttTransitionLock) {
            val lockAcquiredAtMs = nowMs()
            val envelope = runCatching {
                AudioFrameCodec.decode(message, MediaDirection.Downlink)
            }.getOrElse {
                diagnostics.connectionDiagnostic("Invalid downlink media envelope")
                return@synchronized
            }
            audio.onIncomingAudioActivity()
            val ingest = incomingAudio.ingest(envelope, callbackAtMs)
            val ingestedAtMs = nowMs()
            when (ingest) {
            is IncomingIngestResult.Overflow -> {
                val cursor = ingest.cursor
                val repeated = ingest.repeated
                diagnostics.recoveryOverflow(cursor, repeated)
                diagnostics.connectionDiagnostic(
                    "Receive FIFO overflow frames=${incomingAudio.frameCount()} " +
                        "limit=${audioPolicy.receiveFifoFrames}",
                )
                if (repeated) {
                    failRepeatedReceiveOverflow(cursor)
                } else {
                    reconnect()
                }
                return@synchronized
            }
            IncomingIngestResult.Rejected -> {
                diagnostics.connectionDiagnostic("Rejected downlink media envelope")
                val cursor = incomingAudio.listenCursor()
                connection.listen(cursor.burstIndex, cursor.nextSequence)
                return@synchronized
            }
            IncomingIngestResult.Accepted -> Unit
            }
            diagnostics.receiveEnvelopeIngested(
                envelope.burstId,
                envelope.firstSequence,
                envelope.opusPackets.size,
                message.size,
                callbackAtMs,
                lockAcquiredAtMs,
                ingestedAtMs,
                incomingAudio.frameCount(),
            )
            val burstIndex = incomingAudio.burstIndex(envelope.burstId)
            if (burstIndex != null && burstIndex < (backfillBeforeBurstIndex ?: Long.MIN_VALUE)) {
                diagnostics.backfillReceived(
                    envelope.opusPackets.size,
                    message.size,
                )
            }
            pumpIncoming()
            updateDiagnostics()
        }
    }

    private fun handleSnapshot(session: SessionSnapshot) {
        val previousEpoch = serverEpoch
        val awaitingSnapshot = resumePhase as? ResumePhase.AwaitingSnapshot
        val resetReliableState =
            awaitingSnapshot?.resetReliableState == true ||
            (previousEpoch != null && previousEpoch.incarnationId != session.channelIncarnationId)
        val resuming = previousEpoch != null && !resetReliableState
        val interruptionIndicator = when {
            resetReliableState && ptt.localInterruptionNeeded() -> AudioIndicator.TransmissionInterrupted
            resetReliableState && ptt.localRequestActive() -> AudioIndicator.PttRejected
            else -> null
        }
        if (resetReliableState) {
            outgoingAudio.clear()
            incomingAudio.clear()
            pttRequestDeadline = null
            captureDeadlines = null
            invalidateIncomingPlayback()
            diagnostics.backfillReset()
        }
        serverEpoch = ServerEpoch(
            incarnationId = session.channelIncarnationId,
            generation = session.generation.toLong(),
            audioPolicy = session.audioPolicy,
            backfillBeforeBurstIndex = session.nextBurstIndex.takeIf { resuming },
        )
        outgoingAudio.configure(audioPolicy)
        incomingAudio.configure(audioPolicy)
        captureDeadlines = captureDeadlines?.copy(offlineExpiresAtMs = null)
        ackWatchdog = AckWatchdogState(generation = socketGeneration)
        incomingAudio.initialize(session.channelIncarnationId, session.eligibleFromIndex)
        diagnostics.connectionJoined()
        _state.update {
            it.copy(connectionDetail = null, participantCount = session.participantCount)
        }
        audio.setSessionActive(true)
        val receiving = !isEchoChannel(_state.value.currentChannel) &&
            (!incomingAudio.isEmpty() || incomingPlaybackInProgress() || session.floor?.owned == false)
        connectionPhase = ConnectionPhase.Active
        if (resuming) {
            resumePhase = ResumePhase.AwaitingBarriers(
                floor = session.floor,
                captureStopPending = awaitingSnapshot?.captureStopPending == true,
            )
        } else {
            resumePhase = ResumePhase.None
            if (resetReliableState) stopPttQueue(clearNotice = true)
            val actions = if (resetReliableState) {
                ptt.resetAfterServerEpochLoss()
            } else {
                ptt.resumed(session.floor)
            }
            val resumedPtt = actions.any { it is PttAction.Request }
            if (resumedPtt) stopPttQueue(clearNotice = true)
            val requestTimeoutMs = if (resumedPtt && audio.onPttActivity()) {
                PTT_WAKE_REQUEST_TIMEOUT_MS
            } else PTT_REQUEST_TIMEOUT_MS
            perform(actions, requestTimeoutMs) {
                if (interruptionIndicator != null) audio.playIndicator(interruptionIndicator)
            }
            if (receiving) ptt.remoteStarted()
        }
        if (resetReliableState) {
            diagnostics.connectionDiagnostic("Joined as a fresh logical member")
        }
        publishRuntimeState()
        flushSender()
        if (!incomingAudio.isEmpty()) startIncomingPlayback()
        if (receiving) publishRuntimeState()
        if (resuming) advanceResume()
        val cursor = incomingAudio.listenCursor()
        if (resuming) diagnostics.backfillStarted(cursor)
        connection.listen(cursor.burstIndex, cursor.nextSequence)
    }

    private fun handlePttGranted(event: ControlEvent.PttGranted) {
        cancelPttTimeout(event.requestId)
        val grant = ptt.granted(event.requestId, event.burstId)
        val existingBurst = outgoingAudio.burst(event.burstId)
        if (!grant.recognized && existingBurst != null) {
            diagnostics.pttLateGrant(event.requestId)
            flushSender()
            return
        }
        outgoingAudio.start(event.burstId, event.burstIndex, nowMs())
        val actions = if (grant.recognized) grant.actions else listOf(PttAction.Finish(event.burstId))
        if (PttAction.GrantCapture in actions) {
            stopPttQueue(clearNotice = true)
            startBurstDurationDeadline(event.burstId)
            diagnostics.pttGranted(event.requestId)
            _state.update { it.copy(connectionDetail = null, pttError = null) }
        } else {
            diagnostics.pttLateGrant(event.requestId)
        }
        perform(actions)
        publishRuntimeState()
        updateDiagnostics()
    }

    private fun handlePttDenied(event: ControlEvent.PttDenied) {
        cancelPttTimeout(event.requestId)
        val actions = ptt.denied(event.requestId, event.reason)
        if (actions.isEmpty()) return
        diagnostics.pttDenied(event.reason, event.requestId)
        val detail = when (event.reason) {
            "channel_busy" -> "PTT denied: channel busy"
            "server_busy" -> "PTT unavailable: server audio capacity is full"
            else -> "PTT denied: ${event.reason.take(80)}"
        }
        val message = if (event.reason == "channel_busy") "Channel busy" else detail
        _state.update { it.copy(connectionDetail = detail, pttError = message) }
        showPttNotice(
            PttNoticeKind.Rejected,
            if (event.reason == "channel_busy") {
                "Channel busy — release and press PTT again after the channel-free tone."
            } else {
                "$message — release and press PTT again."
            },
        )
        audio.onPttReleased()
        publishRuntimeState()
        perform(actions) { audio.playIndicator(AudioIndicator.PttRejected) }
    }

    private fun handlePttEnded(event: ControlEvent.PttEnded) {
        outgoingAudio.confirmReleased(event.burstId)
        if (captureDeadlines?.burstId == event.burstId) captureDeadlines = null
        updateUplinkQuality()
        val terminal = ptt.ended(event.burstId) ?: return
        val terminalIndicator = terminal.indicator
        val restartAfterRecovery = terminal.actions.any { it is PttAction.Request }
        val indicator = when {
            restartAfterRecovery -> null
            terminalIndicator == LocalTerminalIndicator.Suppress -> null
            event.reason !in setOf("released", "complete") -> AudioIndicator.TransmissionInterrupted
            terminalIndicator == LocalTerminalIndicator.ChannelFree -> AudioIndicator.ChannelFree
            else -> null
        }
        perform(terminal.actions) {
            if (indicator != null) audio.playIndicator(indicator)
            if (restartAfterRecovery) publishRuntimeState()
        }
        publishRuntimeState()
    }

    override fun onReconnecting(attempt: Int) = synchronized(pttTransitionLock) {
        connectionPhase = ConnectionPhase.Reconnecting
        diagnostics.connectionReconnecting(attempt)
        cancelPttTimeout()
        beginPttTransportRecovery()
        resetLinkMetrics()
        _state.update {
            it.copy(
                participantCount = null,
                audioPathQuality = AudioPathQuality.Poor,
            )
        }
        publishRuntimeState()
    }

    override fun onConnectionError() = synchronized(pttTransitionLock) {
        cancelPendingPtt(PENDING_CONNECTION_ERROR)
        beginPttTransportRecovery()
        connectionPhase = ConnectionPhase.Failed
        resetLinkMetrics()
        publishRuntimeState()
        reportError(UiErrorTarget.General, "Connection lost. Tap reconnect to retry.")
    }

    override fun onConnectionDiagnostic(detail: String) {
        diagnostics.connectionDiagnostic(detail)
        _state.update { it.copy(connectionDetail = detail.lineSequence().first().take(200)) }
    }

    override fun onConnectionDisconnected(detail: String) {
        diagnostics.connectionDisconnected(detail)
        _state.update {
            it.copy(
                participantCount = null,
                connectionDetail = detail.lineSequence().first().take(200),
            )
        }
    }

    override fun onAudioQueue(queueBytes: Long, limitExceeded: Boolean) {
        diagnostics.outgoingAudioQueue(queueBytes, limitExceeded)
        synchronized(pttTransitionLock) {
            outgoingQueueBytes = queueBytes
            updateUplinkQuality()
        }
        updateDiagnostics()
    }

    fun close() = synchronized(pttTransitionLock) {
        cancelPendingPtt()
        runtimeScope.cancel()
        audio.close()
        connection.disconnect()
    }

    internal fun runSchedulerTickForTest() = synchronized(pttTransitionLock) {
        runSchedulerTick()
    }

    private fun runSchedulerTick() {
        val now = nowMs()
        processDeadlines(now)
        flushSender()
        advanceResume()
        pumpIncoming()
    }

    private fun startConnection(
        address: String,
        channel: String,
        clearPttError: Boolean = false,
    ) = synchronized(pttTransitionLock) {
        if (serverEpoch != null && resumePhase == ResumePhase.None) {
            beginPttTransportRecovery()
        }
        connectionPhase = ConnectionPhase.Connecting
        resetLinkMetrics()
        _state.update {
            it.copy(
                currentChannel = channel,
                participantCount = null,
                linkMetrics = LinkMetrics(),
                connectionDetail = null,
                pttError = if (clearPttError) null else it.pttError,
            )
        }
        publishRuntimeState()
        connection.connect(address, channel, this)
    }

    private fun startPttRequest() {
        stopPttQueue(clearNotice = true)
        val press = ptt.press()
        diagnostics.pttPressed(press)
        if (!press.requestCreated) {
            val message = when (press.before) {
                SessionStatus.Receiving ->
                    "PTT unavailable while receiving — release and press PTT again."
                SessionStatus.PlayingEcho ->
                    "PTT unavailable while receiving ECHO — release and press PTT again."
                SessionStatus.Releasing ->
                    "The previous transmission is finishing — release and press PTT again."
                else -> PTT_REJECTED_ERROR
            }
            playPttRejected(message)
            publishRuntimeState()
            return
        }
        val requestTimeoutMs = if (audio.onPttActivity()) {
            PTT_WAKE_REQUEST_TIMEOUT_MS
        } else PTT_REQUEST_TIMEOUT_MS
        _state.update {
            it.copy(
                pttError = null,
                pttNotice = null,
            )
        }
        publishRuntimeState()
        perform(press.actions, requestTimeoutMs)
    }

    private fun deferPttUntilConnected() {
        ptt.deferPress()
        val deadline = PttQueueDeadline(nowMs() + PTT_QUEUE_TIMEOUT_MS)
        pttQueueDeadline = deadline
        pttQueueDeadlineJob?.cancel()
        pttQueueDeadlineJob = runtimeScope.launch {
            delay(PTT_QUEUE_TIMEOUT_MS)
            synchronized(pttTransitionLock) {
                if (pttQueueDeadline === deadline && nowMs() >= deadline.expiresAtMs) {
                    cancelPendingPtt(PTT_QUEUE_TIMEOUT_ERROR)
                }
            }
        }
        showPttNotice(PttNoticeKind.Queued, PTT_QUEUED_NOTICE)
        publishRuntimeState()
        audio.startQueuedCue()
    }

    private fun cancelPendingPtt(message: String? = null) = synchronized(pttTransitionLock) {
        val pending = ptt.pendingPhysicalHold()
        stopPttQueue(clearNotice = message == null)
        if (!pending) return@synchronized
        ptt.release()
        publishRuntimeState()
        audio.onPttReleased()
        if (message != null) {
            _state.update { it.copy(pttError = message) }
            showPttNotice(PttNoticeKind.Rejected, message)
            audio.playIndicator(AudioIndicator.PttRejected)
        }
    }

    private fun stopPttQueue(clearNotice: Boolean) {
        pttQueueDeadline = null
        pttQueueDeadlineJob?.cancel()
        pttQueueDeadlineJob = null
        audio.stopQueuedCue()
        if (clearNotice) clearQueuedPttNotice()
    }

    private fun interruptDrainingPlayback(): Boolean {
        if (playbackPhase !is PlaybackPhase.Draining) return false
        playbackPhase = PlaybackPhase.Idle
        audio.interruptIncomingPlayback()
        if (isEchoChannel(_state.value.currentChannel)) {
            ptt.ready()
        } else {
            ptt.remoteEnded()
        }
        publishRuntimeState()
        return true
    }

    private fun perform(
        actions: List<PttAction>,
        requestTimeoutMs: Long = PTT_REQUEST_TIMEOUT_MS,
        onComplete: () -> Unit = {},
    ) {
        val action = actions.firstOrNull()
        if (action == null) {
            onComplete()
            return
        }
        if (action == PttAction.StopCapture) {
            audio.stopCapture {
                synchronized(pttTransitionLock) {
                    perform(actions.drop(1), requestTimeoutMs, onComplete)
                }
            }
            return
        }
        if (perform(action, requestTimeoutMs)) {
            perform(actions.drop(1), requestTimeoutMs, onComplete)
        }
    }

    private fun perform(action: PttAction, requestTimeoutMs: Long = PTT_REQUEST_TIMEOUT_MS): Boolean =
        when (action) {
            is PttAction.Request -> {
                diagnostics.pttRequested(action.requestId, requestTimeoutMs)
                val sent = connection.requestPtt(action.requestId)
                diagnostics.pttRequestSent(action.requestId, sent)
                if (sent) {
                    startPttTimeout(action.requestId, requestTimeoutMs)
                } else {
                    handlePttRequestFailure(action.requestId, PTT_SEND_ERROR)
                }
                true
            }
            is PttAction.Cancel -> {
                cancelPttTimeout(action.requestId)
                val sent = connection.cancelPtt(action.requestId)
                diagnostics.pttReleased(action.requestId, sent)
                ptt.controlSent()
                publishRuntimeState()
                if (!sent) {
                    _state.update { it.copy(pttError = PTT_RELEASE_ERROR) }
                    reportError(UiErrorTarget.General, PTT_RELEASE_ERROR)
                    if (_state.value.currentChannel != null) reconnect()
                    false
                } else true
            }
            is PttAction.Finish -> {
                outgoingAudio.finish(action.burstId)
                if (captureDeadlines?.burstId == action.burstId) captureDeadlines = null
                if (action.interrupted) {
                    ptt.markLocalInterruptionSignaled()
                }
                flushSender()
                true
            }
            PttAction.StartCapture -> {
                diagnostics.captureStarted()
                audio.start(
                    sender = ::storeAndSendAudio,
                    onFailure = ::handleAudioCaptureFailure,
                )
                true
            }
            PttAction.GrantCapture -> {
                audio.grantCapture()
                true
            }
            PttAction.StopCapture -> error("StopCapture must be sequenced asynchronously")
        }

    private fun startPttTimeout(requestId: String, timeoutMs: Long) {
        pttRequestDeadline = PttRequestDeadline(requestId, nowMs() + timeoutMs)
    }

    private fun storeAndSendAudio(packet: ByteArray): Boolean = synchronized(pttTransitionLock) {
        val burstId = ptt.activeBurstId() ?: return@synchronized false
        outgoingAudio.add(burstId, packet, nowMs()) ?: return@synchronized false
        updateUplinkQuality()
        flushSender()
        true
    }

    private fun flushSender(): Boolean {
        val now = nowMs()
        outgoingAudio.expire(now)
        val oldestSentUnacknowledgedAgeMs =
            outgoingAudio.oldestSentUnacknowledgedAgeMs(socketGeneration, now)
        val ackProgressAgeMs = ackWatchdog.progressAtMs
            ?.takeIf { ackWatchdog.generation == socketGeneration }
            ?.let { (now - it).coerceAtLeast(0) }
        if (
            !ackWatchdog.fired &&
            ackWatchdog.generation == socketGeneration &&
            oldestSentUnacknowledgedAgeMs != null &&
            ackProgressAgeMs != null &&
            ackProgressAgeMs >= audioPolicy.ackWatchdogMs
        ) {
            ackWatchdog = ackWatchdog.copy(fired = true)
            diagnostics.connectionDiagnostic(
                "Uplink ACK watchdog expired generation=$socketGeneration " +
                    "age=${ackProgressAgeMs}ms " +
                    "timeout=${audioPolicy.ackWatchdogMs}ms queue=${outgoingQueueBytes}B " +
                    "rtt=${diagnostics.lastRttMs?.let { "${it}ms" } ?: "none"}",
            )
            onReconnecting(0)
            val channel = _state.value.currentChannel
            if (channel != null) connection.connect(_state.value.serverAddress, channel, this)
            return false
        }
        val range = outgoingAudio.nextRange(now, socketGeneration)
        if (range != null) {
            val message = AudioFrameCodec.encode(
                NetworkAudioEnvelope(
                    MediaDirection.Uplink,
                    range.burstId,
                    range.firstSequence,
                    range.packets,
                ),
            )
            val sent = connection.sendAudio(message)
            diagnostics.sourceAudioSent(
                sent,
                range,
                nowMs(),
                outgoingQueueBytes,
                socketGeneration,
            )
            if (sent) {
                if (oldestSentUnacknowledgedAgeMs == null) {
                    ackWatchdog = AckWatchdogState(
                        generation = socketGeneration,
                        progressAtMs = now,
                    )
                }
                outgoingAudio.markRangeSent(range, socketGeneration, now)
                repeat(range.packets.size) { diagnostics.audioSent() }
            }
        }
        outgoingAudio.sendableEnds(socketGeneration).forEach { end ->
            val sent = connection.finishBurst(end.burstId, end.finalNextSequence)
            if (sent) {
                diagnostics.pttReleased(end.burstId, true)
                outgoingAudio.markEndSent(end.burstId, socketGeneration)
            }
        }
        updateUplinkQuality()
        return outgoingAudio.pendingEnds(socketGeneration).isEmpty()
    }

    private fun pumpIncoming() {
        if (playbackPumpJob?.isActive == true) return
        playbackPumpJob = runtimeScope.launch {
            while (true) {
                var waitingForCapacity = false
                val keepRunning = synchronized(pttTransitionLock) {
                    val playbackQueuedFrames = audio.playbackQueuedFrames()
                    if (playbackQueuedFrames >= PLAYBACK_AHEAD_FRAMES) {
                        waitingForCapacity = true
                        true
                    } else when (val item = incomingAudio.poll(audio.playbackBacklogEmpty()).also {
                        publishReceiveQuality()
                    }) {
                        is IncomingPoll.Frame -> {
                            if (audio.play(item.burstId, item.packet)) {
                                val enqueuedAtMs = nowMs()
                                check(incomingAudio.commit(item))
                                diagnostics.receivePlaybackEnqueued(
                                    item.burstId,
                                    item.sequence,
                                    item.receivedAtMs,
                                    item.lastWebSocketAtMs,
                                    enqueuedAtMs,
                                    incomingAudio.frameCount(),
                                    audio.playbackQueuedFrames(),
                                )
                                clearPlaybackEnqueueBlocked()
                                diagnostics.audioFrame(item.burstIndex, item.sequence)
                                publishReceiveQuality()
                                updateDiagnostics()
                                true
                            } else {
                                reportPlaybackEnqueueBlocked()
                                false
                            }
                        }
                        is IncomingPoll.Loss -> {
                            if (audio.playLoss(item.burstId, item.count)) {
                                check(incomingAudio.commit(item))
                                addPlaybackLoss(item.count)
                                diagnostics.audioLoss(
                                    item.burstIndex,
                                    item.firstSequence,
                                    item.count,
                                )
                                publishReceiveQuality()
                                updateDiagnostics()
                                true
                            } else {
                                reportPlaybackEnqueueBlocked()
                                false
                            }
                        }
                        is IncomingPoll.BurstEnded -> {
                            check(incomingAudio.commit(item))
                            diagnostics.receiveBurstFinished(item.burstId)
                            if (incomingAudio.isEmpty()) finishIncomingPlayback()
                            true
                        }
                        IncomingPoll.Waiting -> false
                        IncomingPoll.Empty -> {
                            if (playbackPhase is PlaybackPhase.Active) finishIncomingPlayback()
                            false
                        }
                    }
                }
                if (!keepRunning) break
                if (waitingForCapacity) delay(PLAYBACK_CAPACITY_POLL_MS)
            }
            synchronized(pttTransitionLock) {
                playbackPumpJob = null
            }
        }
    }

    private fun finishIncomingPlayback() {
        val active = playbackPhase as? PlaybackPhase.Active ?: return
        playbackPhase = PlaybackPhase.Draining(active.token, active.metrics)
        publishRuntimeState()
        audio.incomingTransmissionEnded {
            synchronized(pttTransitionLock) {
                val draining = playbackPhase as? PlaybackPhase.Draining ?: return@synchronized
                if (draining.token != active.token || !incomingAudio.isEmpty()) return@synchronized
                playbackPhase = PlaybackPhase.Idle
                if (isEchoChannel(_state.value.currentChannel)) {
                    ptt.ready()
                    publishRuntimeState()
                    audio.playIndicator(AudioIndicator.ChannelFree)
                } else {
                    val actions = ptt.remoteEnded()
                    val restarted = actions.any { it is PttAction.Request }
                    perform(actions)
                    publishRuntimeState()
                    if (!restarted) {
                        audio.playIndicator(AudioIndicator.ChannelFree)
                    }
                }
            }
        }
    }

    private fun failRepeatedReceiveOverflow(cursor: ListenCursor) {
        val detail =
            "Receive FIFO overflow repeated at burst=${cursor.burstIndex} " +
                "sequence=${cursor.nextSequence}"
        failRecovery(detail, "Audio recovery failed. Reconnect manually.")
    }

    private fun failProtocolRecovery(detail: String) =
        failRecovery(detail, "Audio protocol conflict. Reconnect manually.")

    private fun failRecovery(detail: String, message: String) {
        diagnostics.connectionDiagnostic(detail)
        stopForConnectionLoss(notifyInterruption = true)
        connection.disconnect()
        connectionPhase = ConnectionPhase.Failed
        _state.update { it.copy(connectionDetail = detail) }
        publishRuntimeState()
        reportError(UiErrorTarget.General, message)
    }

    private fun clearReliableAudioState() {
        outgoingAudio.clear()
        incomingAudio.clear()
        playbackPumpJob?.cancel()
        playbackPumpJob = null
        resumePhase = ResumePhase.None
        invalidateIncomingPlayback()
        serverEpoch = null
        ackWatchdog = AckWatchdogState()
        captureDeadlines = null
        outgoingQueueBytes = 0
        _state.update { it.copy(audioPathQuality = AudioPathQuality.Good) }
        updateDiagnostics()
    }

    private fun startIncomingPlayback() {
        if (playbackPhase is PlaybackPhase.Active) return
        nextPlaybackToken += 1
        playbackPhase = PlaybackPhase.Active(nextPlaybackToken, PlaybackMetrics())
        publishReceiveQuality()
        audio.incomingTransmissionStarted()
    }

    private fun incomingPlaybackInProgress(): Boolean =
        playbackPhase !is PlaybackPhase.Idle

    private fun invalidateIncomingPlayback() {
        playbackPhase = PlaybackPhase.Idle
        audio.stop()
    }

    private fun reportPlaybackEnqueueBlocked() {
        val active = playbackPhase as? PlaybackPhase.Active ?: return
        if (active.metrics.enqueueBlocked) return
        playbackPhase = active.copy(
            metrics = active.metrics.copy(enqueueBlocked = true, wasBlocked = true),
        )
        publishReceiveQuality()
        diagnostics.connectionDiagnostic("Playback enqueue blocked")
    }

    private fun clearPlaybackEnqueueBlocked() {
        val active = playbackPhase as? PlaybackPhase.Active ?: return
        playbackPhase = active.copy(metrics = active.metrics.copy(enqueueBlocked = false))
    }

    private fun addPlaybackLoss(count: Int) {
        val active = playbackPhase as? PlaybackPhase.Active ?: return
        playbackPhase = active.copy(
            metrics = active.metrics.copy(
                enqueueBlocked = false,
                lostFrames = active.metrics.lostFrames + count,
            ),
        )
    }

    private fun advanceResume() {
        val phase = resumePhase as? ResumePhase.AwaitingBarriers ?: return
        if (phase.captureStopPending) return
        if (outgoingAudio.pendingEnds(socketGeneration).isNotEmpty()) return
        resumePhase = ResumePhase.None
        val floor = phase.floor
        val receiving = !isEchoChannel(_state.value.currentChannel) &&
            (!incomingAudio.isEmpty() || incomingPlaybackInProgress() || floor?.owned == false)
        val actions = ptt.resumed(floor)
        val resumedPtt = actions.any { it is PttAction.Request }
        if (resumedPtt) stopPttQueue(clearNotice = true)
        if (receiving) ptt.remoteStarted()
        publishRuntimeState()
        val requestTimeoutMs = if (resumedPtt && audio.onPttActivity()) {
            PTT_WAKE_REQUEST_TIMEOUT_MS
        } else PTT_REQUEST_TIMEOUT_MS
        perform(actions, requestTimeoutMs)
    }

    private fun beginPttTransportRecovery() {
        val previous = resumePhase as? ResumePhase.AwaitingSnapshot
        val recoveryActions = ptt.connectionLost()
        resumePhase = ResumePhase.AwaitingSnapshot(
            captureStopPending = previous?.captureStopPending == true ||
                PttAction.StopCapture in recoveryActions,
            resetReliableState = previous?.resetReliableState == true,
        )
        startOfflineCaptureDeadline()
        if (recoveryActions.isEmpty()) return
        perform(recoveryActions) {
            synchronized(pttTransitionLock) {
                resumePhase = when (val phase = resumePhase) {
                    is ResumePhase.AwaitingSnapshot -> phase.copy(captureStopPending = false)
                    is ResumePhase.AwaitingBarriers -> phase.copy(captureStopPending = false)
                    ResumePhase.None -> ResumePhase.None
                }
                advanceResume()
            }
        }
    }

    private fun startOfflineCaptureDeadline() {
        if (!ptt.localTransmissionActive()) {
            captureDeadlines = captureDeadlines?.copy(offlineExpiresAtMs = null)
            return
        }
        val burstId = ptt.activeBurstId() ?: return
        val current = captureDeadlines?.takeIf { it.burstId == burstId } ?: return
        if (current.offlineExpiresAtMs == null) {
            captureDeadlines = current.copy(
                offlineExpiresAtMs = nowMs() + audioPolicy.recoveryHorizonMs,
            )
        }
    }

    private fun startBurstDurationDeadline(burstId: String) {
        val remainingMs = outgoingAudio.remainingBurstDurationMs(burstId, nowMs()) ?: return
        captureDeadlines = CaptureDeadlines(
            burstId = burstId,
            burstExpiresAtMs = nowMs() + remainingMs,
        )
    }

    private fun processDeadlines(now: Long) {
        pttQueueDeadline?.takeIf { now >= it.expiresAtMs }?.let {
            cancelPendingPtt(PTT_QUEUE_TIMEOUT_ERROR)
        }
        pttRequestDeadline?.takeIf { now >= it.expiresAtMs }?.let { deadline ->
            pttRequestDeadline = null
            handlePttRequestFailure(deadline.requestId, PTT_TIMEOUT_ERROR)
        }
        val deadlines = captureDeadlines ?: return
        if (ptt.activeBurstId() != deadlines.burstId) {
            captureDeadlines = null
            return
        }
        val burstExpired = now >= deadlines.burstExpiresAtMs
        val offlineExpired = deadlines.offlineExpiresAtMs?.let { now >= it } == true
        if (!burstExpired && !offlineExpired) return
        captureDeadlines = null
        val failure = if (
            burstExpired &&
            deadlines.burstExpiresAtMs <= (deadlines.offlineExpiresAtMs ?: Long.MAX_VALUE)
        ) {
            ptt.durationExpired()
        } else {
            ptt.recoveryExpired()
        }
        perform(failure.actions) {
            audio.playIndicator(AudioIndicator.TransmissionInterrupted)
        }
        publishRuntimeState()
        updateDiagnostics()
    }

    private fun currentUplinkQuality(): AudioPathQuality {
        val ageMs = outgoingAudio.oldestUnacknowledgedAgeMs(nowMs())
        return when {
            ageMs >= audioPolicy.poorAgeMs -> AudioPathQuality.Poor
            ageMs >= audioPolicy.fairAgeMs -> AudioPathQuality.Fair
            else -> AudioPathQuality.Good
        }
    }

    private fun updateUplinkQuality() {
        if (_state.value.status !in DOWNLINK_STATUSES) {
            _state.update { it.copy(audioPathQuality = currentUplinkQuality()) }
        }
    }

    private fun cancelPttTimeout(requestId: String) {
        if (pttRequestDeadline?.requestId == requestId) pttRequestDeadline = null
    }

    private fun handlePttRequestFailure(requestId: String, message: String) = synchronized(pttTransitionLock) {
        val actions = ptt.requestFailed(requestId)
        if (actions.isEmpty()) return@synchronized
        cancelPttTimeout(requestId)
        stopPttQueue(clearNotice = false)
        if (message == PTT_TIMEOUT_ERROR) diagnostics.pttTimedOut(requestId)
        _state.update { it.copy(pttError = message) }
        showPttNotice(
            PttNoticeKind.Rejected,
            if ("press PTT again" in message) message
            else "$message Release and press PTT again.",
        )
        publishRuntimeState()
        audio.onPttReleased()
        perform(actions) {
            audio.playIndicator(AudioIndicator.PttRejected) {
                if (_state.value.currentChannel != null &&
                    _state.value.status != SessionStatus.Connecting
                ) {
                    reconnect()
                }
            }
        }
    }

    private fun handleAudioCaptureFailure(failure: AudioCaptureFailure) =
        synchronized(pttTransitionLock) {
            val result = ptt.localAudioFailed()
            if (result.actions.isEmpty()) return@synchronized
            val message = "Audio unavailable: ${failure.name} — release and press PTT again."
            _state.update { it.copy(pttError = message) }
            if (!result.wasTransmitting) showPttNotice(PttNoticeKind.Rejected, message)
            publishRuntimeState()
            audio.onPttReleased()
            perform(result.actions) {
                audio.playIndicator(
                    if (result.wasTransmitting) AudioIndicator.TransmissionInterrupted
                    else AudioIndicator.PttRejected,
                )
            }
        }

    private fun cancelPttTimeout() {
        pttRequestDeadline = null
    }

    private fun stopForConnectionLoss(
        notifyInterruption: Boolean = false,
    ) = synchronized(pttTransitionLock) {
        stopPttQueue(clearNotice = true)
        _state.update { it.copy(participantCount = null) }
        cancelPttTimeout()
        val failureIndicator = when {
            !notifyInterruption -> null
            ptt.localInterruptionNeeded() -> AudioIndicator.TransmissionInterrupted
            ptt.status == SessionStatus.Requesting -> AudioIndicator.PttRejected
            else -> null
        }
        ptt.ready()
        connectionPhase = ConnectionPhase.Stopped
        clearReliableAudioState()
        val deactivateRoute = { audio.setSessionActive(false) }
        if (failureIndicator != null) {
            audio.playIndicator(failureIndicator) {
                deactivateRoute()
            }
        } else {
            deactivateRoute()
        }
    }

    private fun handleEchoUnavailable() {
        stopPttQueue(clearNotice = true)
        cancelPttTimeout()
        captureDeadlines = null
        outgoingAudio.clear()
        incomingAudio.clear()
        invalidateIncomingPlayback()
        val interrupted = ptt.localInterruptionNeeded() || ptt.localRequestActive()
        val actions = ptt.resetAfterServerEpochLoss()
        perform(actions) {
            if (interrupted) audio.playIndicator(AudioIndicator.TransmissionInterrupted)
        }
    }

    private fun publishRuntimeState() {
        val status = when (connectionPhase) {
            ConnectionPhase.Stopped -> SessionStatus.Connecting
            ConnectionPhase.Connecting -> SessionStatus.Connecting
            ConnectionPhase.Active -> ptt.status
            ConnectionPhase.Reconnecting -> SessionStatus.Reconnecting
            ConnectionPhase.Failed -> SessionStatus.ConnectionError
        }
        val effectiveStatus = if (
            connectionPhase == ConnectionPhase.Active &&
            _state.value.currentChannel == ECHO_CHANNEL &&
            _state.value.participantCount != 2
        ) SessionStatus.Connecting else status
        val playbackActive = incomingPlaybackInProgress()
        _state.update {
            val quality = when (effectiveStatus) {
                SessionStatus.Requesting,
                SessionStatus.Transmitting,
                SessionStatus.Releasing,
                -> currentUplinkQuality()
                SessionStatus.Receiving,
                SessionStatus.PlayingEcho,
                -> currentReceiveQuality()
                else -> it.audioPathQuality
            }
            it.copy(
                status = effectiveStatus,
                pendingPtt = ptt.pendingPhysicalHold(),
                audioPathQuality = quality,
                playbackActive = playbackActive,
                playbackInterruptible = playbackPhase is PlaybackPhase.Draining,
            )
        }
    }

    private fun publishReceiveQuality() {
        if (_state.value.status !in DOWNLINK_STATUSES) return
        _state.update { it.copy(audioPathQuality = currentReceiveQuality()) }
    }

    private fun currentReceiveQuality(): AudioPathQuality = receiveAudioPathQuality(
        lostFrames = playbackPhase.metrics().lostFrames,
        playbackBlocked = playbackPhase.metrics().wasBlocked,
    )

    private fun resetLinkMetrics() {
        diagnostics.resetLinkMetrics()
        updateDiagnostics()
    }

    private fun updateDiagnostics() = _state.update {
        it.copy(
            diagnostics = diagnostics.reliabilitySummary() +
                " | H ${audioPolicy.recoveryHorizonMs}ms" +
                " | Unacked ${outgoingAudio.frameCount()}" +
                " | Rx ${incomingAudio.frameCount()}/${incomingAudio.maxFrameCount}" +
                " | Buffer ${RECEIVE_BUFFER_TARGET_MS}ms",
            linkMetrics = diagnostics.linkMetrics(),
        )
    }

    private companion object {
        const val PTT_REQUEST_TIMEOUT_MS = 2_000L
        const val PTT_WAKE_REQUEST_TIMEOUT_MS = 5_000L
        const val PTT_QUEUE_TIMEOUT_MS = 5_000L
        const val PTT_QUEUED_NOTICE = "PTT queued — keep holding and wait for the grant tone."
        const val PTT_QUEUE_TIMEOUT_ERROR =
            "Connection unavailable — release and press PTT again."
        const val PTT_REJECTED_ERROR = "PTT unavailable — release and press PTT again."
        const val ECHO_UNAVAILABLE_ERROR = "Echo is connecting — release and press PTT again."
        const val PTT_TIMEOUT_ERROR = "PTT request timed out — release and press PTT again."
        const val PTT_SEND_ERROR = "Unable to send PTT request. Reconnecting."
        const val PTT_RELEASE_ERROR = "Unable to release PTT. Reconnecting."
        const val PENDING_CONNECTION_ERROR =
            "Unable to connect — release and press PTT again."
        const val SENDER_SCHEDULER_INTERVAL_MS = 40L
        const val PLAYBACK_AHEAD_FRAMES = 5
        const val PLAYBACK_CAPACITY_POLL_MS = 5L
        val DOWNLINK_STATUSES = setOf(SessionStatus.Receiving, SessionStatus.PlayingEcho)
    }
}

private data class PlaybackMetrics(
    val lostFrames: Long = 0,
    val enqueueBlocked: Boolean = false,
    val wasBlocked: Boolean = false,
)

private enum class ConnectionPhase { Stopped, Connecting, Active, Reconnecting, Failed }

private data class ServerEpoch(
    val incarnationId: String,
    val generation: Long,
    val audioPolicy: AudioPolicy,
    val backfillBeforeBurstIndex: Long?,
)

private sealed interface ResumePhase {
    data object None : ResumePhase
    data class AwaitingSnapshot(
        val captureStopPending: Boolean = false,
        val resetReliableState: Boolean = false,
    ) : ResumePhase
    data class AwaitingBarriers(
        val floor: FloorSnapshot?,
        val captureStopPending: Boolean,
    ) : ResumePhase
}

private data class PttRequestDeadline(val requestId: String, val expiresAtMs: Long)
private data class PttQueueDeadline(val expiresAtMs: Long)

private data class CaptureDeadlines(
    val burstId: String,
    val burstExpiresAtMs: Long,
    val offlineExpiresAtMs: Long? = null,
)

private data class AckWatchdogState(
    val generation: Long = 0,
    val progressAtMs: Long? = null,
    val fired: Boolean = false,
)

private sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase
    data class Active(val token: Long, val metrics: PlaybackMetrics) : PlaybackPhase
    data class Draining(val token: Long, val metrics: PlaybackMetrics) : PlaybackPhase
}

private fun PlaybackPhase.metrics(): PlaybackMetrics = when (this) {
    PlaybackPhase.Idle -> PlaybackMetrics()
    is PlaybackPhase.Active -> metrics
    is PlaybackPhase.Draining -> metrics
}

internal fun receiveAudioPathQuality(
    lostFrames: Long,
    playbackBlocked: Boolean,
): AudioPathQuality = when {
    playbackBlocked || lostFrames > 3 -> AudioPathQuality.Poor
    lostFrames > 0 -> AudioPathQuality.Fair
    else -> AudioPathQuality.Good
}
