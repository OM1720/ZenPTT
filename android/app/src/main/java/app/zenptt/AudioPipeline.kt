// Coordinates audio routing, capture, playback jobs, and idle power-saving state.
package app.zenptt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque

enum class AudioRouteStatus(val label: String) {
    Inactive("Inactive"),
    Preparing("Preparing"),
    BluetoothHeadset("Bluetooth headset"),
    Phone("Phone"),
    Standby("Power-saving standby"),
    Unavailable("Unavailable"),
}

internal data class AudioPipelineSnapshot(
    val routeStatus: AudioRouteStatus,
    val capturing: Boolean,
    val queuedFrames: Int,
    val maxQueuedFrames: Int,
    val playbackGeneration: Long,
    val playbackStarts: Int,
    val playbackStops: Int,
    val playbackStopReason: String,
    val playbackMaxWriteAheadMs: Long,
)

internal enum class AudioLifecycleObservation {
    CaptureStarting,
    CaptureReleased,
    PlaybackReleased,
}


class AudioPipeline(context: Context) : AudioGate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diagnosticEvents = ArrayDeque<String>()
    @Volatile
    private var playbackObserver: ((PlaybackObservation) -> Unit)? = null
    @Volatile
    private var playbackStartBarrier: (suspend () -> Unit)? = null
    @Volatile
    private var lifecycleObserver: ((AudioLifecycleObservation) -> Unit)? = null
    private val routeController = AudioRouteController(context, ::recordDiagnostic)
    private val capture = AudioCapture(
        routeController,
        ::recordDiagnostic,
        { observation -> lifecycleObserver?.invoke(observation) },
    )
    private val indicatorPlayer = AudioIndicatorPlayer(routeController, ::recordDiagnostic)
    private val playback = AudioPlayback(
        MAX_BUFFERED_AUDIO_FRAMES,
        { PLAYBACK_HARD_LIMIT_MS.toLong() },
        routeController,
        ::recordDiagnostic,
        { observation -> playbackObserver?.invoke(observation) },
        { observation -> lifecycleObserver?.invoke(observation) },
    )
    val routeStatus: StateFlow<AudioRouteStatus> = routeController.status
    private var routeChangeJob: Job? = null
    private var playbackBeforeRouteChange: Job? = null
    private var preparingRouteChange = false
    var onInputRouteLost: () -> Unit = {}

    init {
        routeController.devicesChanged = { lost ->
            scope.launch(Dispatchers.Main.immediate) {
                if (lost && capture.isCapturing()) onInputRouteLost()
                if (lost && powerSaveState.isActive() && !powerSaveState.isSleeping()) {
                    scope.launch { routeController.prepare("device_disconnected", newPhrase = false) }
                }
                // Additions are considered by the next phrase, never by a competing audio job.
            }
        }
    }

    fun setHeadsetEnabled(enabled: Boolean) = routeController.setHeadsetEnabled(enabled)

    // New speech waits here; the current burst drains and its closing indicators finish first.
    internal fun atRouteBoundary(canApply: () -> Boolean, apply: () -> Boolean): Job {
        recordDiagnostic("route_change waiting_for_phrase")
        val work = synchronized(jobLock) {
            val barriers = listOfNotNull(routeChangeJob, captureJob, playbackJob, stopJob, sessionRouteJob)
            playbackBeforeRouteChange = playbackJob
            scope.launch(start = CoroutineStart.LAZY) {
                barriers.forEach { it.join() }
                try {
                    while (true) {
                        val cleanup = synchronized(jobLock) {
                            indicatorJobs.toList() + listOfNotNull(captureJob, stopJob)
                        }
                        cleanup.forEach { it.join() }
                        val applied = withContext(Dispatchers.Main.immediate) {
                            val ready = canApply() && synchronized(jobLock) {
                                if (indicatorJobs.isNotEmpty() || captureJob?.isActive == true ||
                                    stopJob?.isActive == true) false
                                else { preparingRouteChange = true; true }
                            }
                            if (ready) apply() else null
                        }
                        if (applied != null) {
                            if (applied && powerSaveState.isActive() && !powerSaveState.isSleeping()) {
                                routeController.prepare("settings")
                            }
                            recordDiagnostic("route_change applied=$applied")
                            break
                        }
                        delay(ROUTE_CHANGE_POLL_MS)
                    }
                } finally {
                    synchronized(jobLock) { preparingRouteChange = false }
                }
            }.also { routeChangeJob = it }
        }
        work.start()
        return work
    }


    override fun close() = close {}

    internal fun close(onClosed: () -> Unit) {
        powerSaveState.setActive(false)
        idleMonitorJob?.cancel()
        synchronized(jobLock) { sessionRouteJob?.cancel(); routeChangeJob?.cancel() }
        routeController.unregister()
        stopInternal(onStopped = {}, onCleaned = {
            routeController.clear("close", AudioRouteStatus.Inactive, AudioRouteStatus.Inactive)
            runCatching(onClosed).onFailure { Log.e(TAG, "audio_close_callback_failed", it) }
            scope.cancel()
        })
    }

    override fun setPowerSaveTimeoutMinutes(minutes: Int) {
        val safeMinutes = minutes.coerceIn(
            MIN_POWER_SAVE_TIMEOUT_MINUTES,
            MAX_POWER_SAVE_TIMEOUT_MINUTES,
        )
        powerSaveTimeoutMs.set(safeMinutes * MILLIS_PER_MINUTE)
        powerSaveState.touch()
        if (powerSaveState.isActive() && !powerSaveState.isSleeping()) startIdleMonitor()
        recordDiagnostic("power_save timeout=${safeMinutes}m")
    }

    override fun onPttActivity(): Boolean = recordActivity("ptt", doubleToneOnWake = true)

    override fun onPttReleased() {
        powerSaveState.clearDoubleTone()
    }

    override fun onIncomingAudioActivity() {
        powerSaveState.clearDoubleTone()
        recordActivity("incoming_audio", doubleToneOnWake = false)
    }

    override fun setSessionActive(active: Boolean) {
        if (!powerSaveState.setActive(active)) return
        if (active) routeController.setStatus(AudioRouteStatus.Preparing)
        idleMonitorJob?.cancel()
        synchronized(jobLock) {
            sessionRouteJob?.cancel()
            val barriers = listOfNotNull(stopJob, captureJob, playbackJob) +
                indicatorJobs.toList()
            sessionRouteJob = if (active) {
                scope.launch { routeController.prepare("session", newPhrase = false) }
            } else {
                scope.launch {
                    barriers.forEach { it.join() }
                    routeController.clear("session", AudioRouteStatus.Inactive, AudioRouteStatus.Inactive)
                }
            }
        }
        if (!active) routeController.setStatus(AudioRouteStatus.Inactive)
        if (active) startIdleMonitor()
        recordDiagnostic("session active=$active")
    }
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var stopJob: Job? = null
    private var sessionRouteJob: Job? = null
    private var idleMonitorJob: Job? = null
    private var indicatorJob: Job? = null
    private var queuedCueTimerJob: Job? = null
    private var queuedCueEnabled = false
    private var queuedCueSchedule: QueuedCueSchedule? = null
    private val indicatorJobs = mutableSetOf<Job>()
    private val jobLock = Any()
    private val powerSaveState = AudioPowerSaveState(SystemClock::elapsedRealtime)
    private val powerSaveTimeoutMs = AtomicLong(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES * MILLIS_PER_MINUTE)

    override fun setAudioBacklogMs(milliseconds: Int) {
        // Protocol v4 owns a fixed 100 ms playout target; this remains a compatibility no-op.
    }

    override fun playIndicator(indicator: AudioIndicator, onComplete: () -> Unit) {
        scheduleIndicator(indicator, onComplete)
    }

    override fun startQueuedCue() {
        synchronized(jobLock) {
            if (queuedCueEnabled) return
            queuedCueEnabled = true
            queuedCueSchedule = QueuedCueSchedule().apply {
                start(SystemClock.elapsedRealtime())
            }
        }
        scheduleNextQueuedCue()
        recordDiagnostic("queued_cue started interval=${QUEUED_CUE_INTERVAL_MS}ms")
    }

    override fun stopQueuedCue() {
        val changed = synchronized(jobLock) {
            val wasEnabled = queuedCueEnabled
            queuedCueEnabled = false
            queuedCueTimerJob?.cancel()
            queuedCueTimerJob = null
            queuedCueSchedule = null
            wasEnabled
        }
        if (changed) recordDiagnostic("queued_cue stopped")
    }

    private fun scheduleNextQueuedCue() {
        val timer = synchronized(jobLock) {
            if (!queuedCueEnabled) return
            val now = SystemClock.elapsedRealtime()
            val dueAt = requireNotNull(queuedCueSchedule).nextBoundary(now)
            scope.launch(start = CoroutineStart.LAZY) {
                delay((dueAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                val played = synchronized(jobLock) {
                    queuedCueTimerJob = null
                    if (!queuedCueEnabled || indicatorJob != null) {
                        false
                    } else {
                        scheduleIndicator(AudioIndicator.PttQueued, ::scheduleNextQueuedCue)
                        true
                    }
                }
                if (!played) scheduleNextQueuedCue()
            }.also { queuedCueTimerJob = it }
        }
        timer.start()
    }

    override fun incomingTransmissionStartedAfterIndicator(indicator: AudioIndicator) {
        playback.transmissionStarted()
        scheduleIndicator(indicator, onComplete = {})
    }

    private fun scheduleIndicator(indicator: AudioIndicator, onComplete: () -> Unit) {
        val job = synchronized(jobLock) {
            val previous = indicatorJob
            val routeBarrier = routeChangeJob.takeIf { preparingRouteChange }
            lateinit var work: Job
            work = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                routeBarrier?.join()
                var complete = false
                try {
                    indicatorPlayer.play(indicator)
                    complete = true
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    complete = true
                    recordDiagnostic(
                        "indicator failure type=${indicator.name} " +
                            "${error.javaClass.simpleName}:${error.message}",
                    )
                    Log.e(TAG, "audio_indicator_failed", error)
                } finally {
                    if (complete) runCatching(onComplete).onFailure {
                        Log.e(TAG, "audio_indicator_callback_failed", it)
                    }
                    synchronized(jobLock) {
                        indicatorJobs.remove(work)
                        if (indicatorJob === work) indicatorJob = null
                    }
                }
            }
            indicatorJob = work
            indicatorJobs += work
            work
        }
        job.start()
    }

    override fun incomingTransmissionStarted() = playback.transmissionStarted()

    override fun incomingTransmissionEnded(onPlaybackDrained: () -> Unit) {
        val completedImmediately = synchronized(jobLock) {
            val completed = playback.transmissionEnded(playbackJob?.isActive == true, onPlaybackDrained)
            if (!completed) startPlaybackLocked()
            completed
        }
        if (completedImmediately) playback.completeImmediately(onPlaybackDrained)
    }

    override fun start(sender: (ByteArray) -> Boolean) = start(sender, onFailure = {})

    override fun start(sender: (ByteArray) -> Boolean, onFailure: (AudioCaptureFailure) -> Unit) {
        synchronized(jobLock) {
            if (captureJob?.isActive == true) return
            capture.prepareForRequest()
            val stopBarrier = stopJob
            val playbackBarrier = if (routeChangeJob?.isActive == true && !preparingRouteChange)
                playbackBeforeRouteChange else playbackJob
            // A previously queued PTT grant must finish on the old route before a pending change.
            val routeBarrier = routeChangeJob.takeIf { preparingRouteChange }
            captureJob = scope.launch {
                routeBarrier?.join()
                stopBarrier?.join()
                playbackBarrier?.join()
                val indicatorBarrier = synchronized(jobLock) { indicatorJob }
                indicatorBarrier?.join()
                capture(sender, onFailure)
            }
        }
    }

    override fun grantCapture() = capture.grant()

    override fun stopCapture(onStopped: () -> Unit) {
        capture.requestStop()
        val cleanup = synchronized(jobLock) {
            val previousStop = stopJob
            val captureWork = captureJob
            captureJob = null
            captureWork?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
                previousStop?.join()
                captureWork?.join()
                runCatching(onStopped).onFailure {
                    Log.e(TAG, "audio_capture_stop_callback_failed", it)
                }
            }.also { stopJob = it }
        }
        cleanup.start()
    }

    override fun stop(onStopped: () -> Unit) = stopInternal(onStopped, onCleaned = {})

    private fun stopInternal(
        onStopped: () -> Unit,
        onCleaned: () -> Unit,
    ) {
        capture.requestStop()
        playback.stop()
        stopQueuedCue()
        val cleanup = synchronized(jobLock) {
            val previousStop = stopJob
            val captureWork = captureJob
            val playbackWork = playbackJob
            val indicatorWork = indicatorJobs.toList()
            captureJob = null
            playbackJob = null
            indicatorJob = null
            indicatorJobs.clear()
            captureWork?.cancel()
            playbackWork?.cancel()
            indicatorWork.forEach(Job::cancel)
            scope.launch(start = CoroutineStart.LAZY) {
                previousStop?.join()
                captureWork?.join()
                playbackWork?.join()
                indicatorWork.forEach { it.join() }
                runCatching(onStopped).onFailure { Log.e(TAG, "audio_stop_callback_failed", it) }
                runCatching(onCleaned).onFailure { Log.e(TAG, "audio_cleanup_callback_failed", it) }
            }.also { stopJob = it }
        }
        cleanup.start()
    }

    override fun play(burstId: String, message: ByteArray): Boolean = synchronized(jobLock) {
        playback.enqueue(burstId, message).also { accepted ->
            if (accepted) startPlaybackLocked()
        }
    }

    override fun playLoss(burstId: String, frameCount: Int): Boolean = synchronized(jobLock) {
        playback.enqueueLoss(burstId, frameCount).also { accepted ->
            if (accepted) startPlaybackLocked()
        }
    }

    override fun playbackQueuedFrames(): Int = playback.snapshot().queuedFrames

    override fun playbackBacklogEmpty(): Boolean = playback.backlogEmpty()

    internal fun observePlayback(observer: ((PlaybackObservation) -> Unit)?) {
        playbackObserver = observer
    }

    internal fun setPlaybackStartBarrier(barrier: (suspend () -> Unit)?) {
        playbackStartBarrier = barrier
    }

    internal fun observeLifecycle(observer: ((AudioLifecycleObservation) -> Unit)?) {
        lifecycleObserver = observer
    }

    internal suspend fun awaitCleanup() {
        val work = synchronized(jobLock) {
            listOfNotNull(stopJob, captureJob, playbackJob) + indicatorJobs.toList()
        }
        work.forEach { it.join() }
    }

    override fun interruptIncomingPlayback() {
        playback.stop()
        val work = synchronized(jobLock) { playbackJob }
        work?.cancel()
        recordDiagnostic("playback interrupted for PTT")
    }

    private fun startPlaybackLocked() {
        if (playbackJob?.isActive == true) return
        val stopBarrier = stopJob
        val indicatorBarrier = indicatorJob
        val routeBarrier = routeChangeJob
        val job = scope.launch(start = CoroutineStart.LAZY) {
            routeBarrier?.join()
            stopBarrier?.join()
            indicatorBarrier?.join()
            playback(requireNotNull(currentCoroutineContext()[Job]))
        }
        playbackJob = job
        job.start()
    }

    private suspend fun capture(
        sender: (ByteArray) -> Boolean,
        onFailure: (AudioCaptureFailure) -> Unit,
    ) {
        synchronized(jobLock) { sessionRouteJob }?.join()
        val routeReady = routeController.prepare("capture")
        try {
            val failure = if (routeReady) {
                capture.run(
                    sender,
                    powerSaveState::consumeGrantToneCount,
                )
            } else {
                AudioCaptureFailure.RouteUnavailable
            }
            if (failure != null) runCatching { onFailure(failure) }.onFailure {
                Log.e(TAG, "audio_capture_failure_callback_failed", it)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDiagnostic("capture pipeline_failure ${error.javaClass.simpleName}:${error.message}")
            runCatching { onFailure(AudioCaptureFailure.RecorderUnavailable) }.onFailure {
                Log.e(TAG, "audio_capture_failure_callback_failed", it)
            }
        } finally {
            if (!powerSaveState.isActive()) {
                routeController.clear("capture", AudioRouteStatus.Inactive, AudioRouteStatus.Inactive)
            }
        }
    }

    private suspend fun playback(owner: Job) {
        synchronized(jobLock) { sessionRouteJob }?.join()
        playbackStartBarrier?.invoke()
        try {
            if (routeController.prepare("playback")) playback.run()
            else { playback.stop(); recordDiagnostic("playback route_unavailable") }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            playback.stop()
            recordDiagnostic("playback failure ${error.javaClass.simpleName}:${error.message}")
            Log.e(TAG, "audio_playback_failed", error)
        } finally {
            if (!powerSaveState.isActive()) {
                routeController.clear("playback", AudioRouteStatus.Inactive, AudioRouteStatus.Inactive)
            }
            synchronized(jobLock) {
                if (playbackJob === owner) {
                    playbackJob = null
                    if (playback.hasQueuedFrames()) startPlaybackLocked()
                }
            }
        }
    }


    internal fun snapshot(): AudioPipelineSnapshot {
        val playbackSnapshot = playback.snapshot()
        return AudioPipelineSnapshot(
            routeStatus = routeStatus.value,
            capturing = capture.isCapturing(),
            queuedFrames = playbackSnapshot.queuedFrames,
            maxQueuedFrames = playbackSnapshot.maxQueuedFrames,
            playbackGeneration = playbackSnapshot.generation,
            playbackStarts = playbackSnapshot.starts,
            playbackStops = playbackSnapshot.stops,
            playbackStopReason = playbackSnapshot.stopReason,
            playbackMaxWriteAheadMs = playbackSnapshot.maxWriteAheadMs,
        )
    }

    override fun debugReport(): String {
        val routeFields = routeController.debugReportFields()
        val eventSnapshot = synchronized(diagnosticEvents) { diagnosticEvents.toList() }
        return buildString {
            appendLine(routeFields.first())
            appendLine("audio.power_save_timeout=${powerSaveTimeoutMs.get() / MILLIS_PER_MINUTE}m")
            appendLine(
                "audio.power_save_state=" + when {
                    !powerSaveState.isActive() -> "inactive"
                    powerSaveState.isSleeping() -> "sleeping"
                    else -> "awake"
                },
            )
            appendLine("audio.playback_hard_limit=${PLAYBACK_HARD_LIMIT_MS}ms")
            playback.debugReportFields().forEach(::appendLine)
            routeFields.drop(1).forEach(::appendLine)
            append("audio.events=")
            if (eventSnapshot.isEmpty()) append("none") else append("\n${eventSnapshot.joinToString("\n")}")
        }
    }

    private fun recordActivity(source: String, doubleToneOnWake: Boolean): Boolean {
        if (!powerSaveState.isActive()) return false
        val wakeReason = synchronized(jobLock) {
            val wasSleeping = powerSaveState.isSleeping()
            if (!powerSaveState.activity(powerSaveTimeoutMs.get(), doubleToneOnWake)) {
                null
            } else {
                routeController.setStatus(AudioRouteStatus.Preparing)
                sessionRouteJob?.cancel()
                sessionRouteJob = scope.launch {
                    routeController.prepare("${source}_wake")
                }
                if (wasSleeping) "sleeping" else "expired_idle"
            }
        }
        if (wakeReason != null) {
            startIdleMonitor()
            recordDiagnostic(
                "power_save wake source=$source reason=$wakeReason double_tone=$doubleToneOnWake",
            )
        }
        return wakeReason != null
    }


    private fun startIdleMonitor() {
        idleMonitorJob?.cancel()
        idleMonitorJob = scope.launch {
            while (isActive && powerSaveState.isActive() && !powerSaveState.isSleeping()) {
                val timeoutMs = powerSaveTimeoutMs.get()
                val remainingMs = powerSaveState.remainingUntilSleep(timeoutMs)
                if (remainingMs > 0) {
                    delay(remainingMs)
                    continue
                }
                val audioBusy = capture.isCapturing() || synchronized(jobLock) {
                    captureJob?.isActive == true || playbackJob?.isActive == true
                }
                if (audioBusy) {
                    delay(IDLE_BUSY_RECHECK_MS)
                    continue
                }
                val enteredSleep = synchronized(jobLock) {
                    if (!powerSaveState.tryEnterSleep(powerSaveTimeoutMs.get(), audioBusy)) {
                        false
                    } else {
                        routeController.setStatus(AudioRouteStatus.Standby)
                        sessionRouteJob?.cancel()
                        sessionRouteJob = scope.launch {
                            routeController.clear(
                                "power_save",
                                AudioRouteStatus.Standby,
                                AudioRouteStatus.Unavailable,
                            )
                        }
                        true
                    }
                }
                if (enteredSleep) {
                    recordDiagnostic("power_save entered")
                    return@launch
                }
            }
        }
    }


    private fun recordDiagnostic(message: String) {
        val entry = "${System.currentTimeMillis()} $message"
        synchronized(diagnosticEvents) {
            if (diagnosticEvents.size == MAX_DIAGNOSTIC_EVENTS) diagnosticEvents.removeFirst()
            diagnosticEvents.addLast(entry)
        }
        Log.d(TAG, message)
    }


    private companion object {
        const val TAG = "ZenPTT.Audio"
        const val PLAYBACK_HARD_LIMIT_MS = 4_000
        const val MAX_BUFFERED_AUDIO_FRAMES = PLAYBACK_HARD_LIMIT_MS / AudioConstants.FRAME_DURATION_MS
        const val IDLE_BUSY_RECHECK_MS = 1_000L
        const val ROUTE_CHANGE_POLL_MS = 25L
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_DIAGNOSTIC_EVENTS = 60
    }
}

internal class QueuedCueSchedule(
    private val intervalMs: Long = QUEUED_CUE_INTERVAL_MS,
) {
    private var nextAtMs: Long? = null

    fun start(nowMs: Long) {
        nextAtMs = nowMs
    }

    fun nextBoundary(nowMs: Long): Long {
        var dueAt = requireNotNull(nextAtMs)
        while (dueAt < nowMs) dueAt += intervalMs
        nextAtMs = dueAt + intervalMs
        return dueAt
    }
}

private const val QUEUED_CUE_INTERVAL_MS = 800L
