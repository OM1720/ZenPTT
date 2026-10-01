// Decodes incoming Opus frames through a bounded queue and drains them to AudioTrack.
package app.zenptt

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel

private sealed interface PlaybackEvent {
    data class Frame(val generation: Long, val burstId: String, val message: ByteArray) : PlaybackEvent
    data class Loss(val generation: Long, val burstId: String, val frameCount: Int) : PlaybackEvent
    data class End(val generation: Long, val onPlaybackDrained: () -> Unit) : PlaybackEvent
}

private const val PCM_BYTES_PER_SAMPLE = 2L
private const val PLAYBACK_WRITE_AHEAD_MS = 100L
private const val MILLIS_PER_SECOND = 1_000L
private const val PLAYBACK_WRITE_AHEAD_FRAMES =
    AudioConstants.PLAYBACK_SAMPLE_RATE * PLAYBACK_WRITE_AHEAD_MS / MILLIS_PER_SECOND

internal sealed interface PlaybackObservation {
    data class DecoderReset(val burstId: String) : PlaybackObservation
    data class Pcm(val burstId: String, val bytes: ByteArray) : PlaybackObservation
}

internal data class AudioPlaybackSnapshot(
    val queuedFrames: Int,
    val maxQueuedFrames: Int,
    val generation: Long,
    val starts: Int,
    val stops: Int,
    val stopReason: String,
    val maxWriteAheadMs: Long,
)

internal class PlaybackUnderrunDiagnostics(private val maxEvents: Int = 10) {
    var observedCount: Int = 0
        private set
    var recordedEvents: Int = 0
        private set

    fun observe(
        currentCount: Int,
        framesWritten: Long,
        playbackHeadFrames: Long,
        queuedFrames: Int,
        queueWaitMs: Long,
        writeGapMs: Long,
    ): String? {
        if (currentCount <= observedCount) return null
        val delta = currentCount - observedCount
        observedCount = currentCount
        if (recordedEvents >= maxEvents) return null
        recordedEvents++
        val aheadFrames = (framesWritten - playbackHeadFrames).coerceAtLeast(0)
        val aheadMs = aheadFrames * MILLIS_PER_SECOND / AudioConstants.PLAYBACK_SAMPLE_RATE
        return "playback underrun count=$currentCount delta=$delta " +
            "written=$framesWritten head=$playbackHeadFrames ahead=${aheadMs}ms " +
            "queued=$queuedFrames queue_wait=${queueWaitMs.coerceAtLeast(0)}ms " +
            "write_gap=${writeGapMs.coerceAtLeast(0)}ms"
    }
}

internal class PlaybackStreamDecoder(
    private val decoder: OpusDecoder = OpusDecoder(),
    private val onReset: (String) -> Unit = {},
) : AutoCloseable {
    private var currentBurstId: String? = null

    fun decode(burstId: String, message: ByteArray): List<ByteArray> {
        enter(burstId)
        return decoder.decode(message)
    }

    fun decodeLoss(burstId: String, frameCount: Int): List<ByteArray> {
        enter(burstId)
        return decoder.decodeLoss(frameCount)
    }

    fun resetForLongLoss(burstId: String) {
        if (!enter(burstId)) reset(burstId)
    }

    private fun enter(burstId: String): Boolean {
        val changed = currentBurstId != null && currentBurstId != burstId
        if (changed) reset(burstId)
        currentBurstId = burstId
        return changed
    }

    private fun reset(burstId: String) {
        decoder.reset()
        onReset(burstId)
    }

    override fun close() = decoder.close()
}

internal class AudioPlayback(
    maxBufferedFrames: Int,
    private val audioBacklogMs: () -> Long,
    private val routeController: AudioRouteController,
    private val recordDiagnostic: (String) -> Unit,
    private val observe: (PlaybackObservation) -> Unit,
    private val observeLifecycle: (AudioLifecycleObservation) -> Unit = {},
) {
    private val queue = Channel<PlaybackEvent>(capacity = maxBufferedFrames + 1)
    private val queueState = PlaybackQueueState(maxBufferedFrames)
    private val incomingTransmissionActive = AtomicBoolean(false)
    private val metricsGeneration = AtomicLong()
    private val transmissionStartedAt = AtomicLong()
    private val firstFrameDelayMs = AtomicLong(METRIC_NOT_RECORDED)
    private val trackStartDelayMs = AtomicLong(METRIC_NOT_RECORDED)
    private val firstPcmDelayMs = AtomicLong(METRIC_NOT_RECORDED)
    private val trackStarts = AtomicInteger()
    private val trackStops = AtomicInteger()
    private val stopReason = AtomicReference("none")
    private val maxWriteAheadFrames = AtomicLong()
    private val activeTrack = AtomicReference<AudioTrack?>()
    private val activeFramesWritten = AtomicLong()

    fun transmissionStarted() {
        incomingTransmissionActive.set(true)
        val generation = queueState.transmissionStarted()
        metricsGeneration.set(generation)
        transmissionStartedAt.set(SystemClock.elapsedRealtime())
        firstFrameDelayMs.set(METRIC_NOT_RECORDED)
        trackStartDelayMs.set(METRIC_NOT_RECORDED)
        firstPcmDelayMs.set(METRIC_NOT_RECORDED)
        trackStarts.set(0)
        trackStops.set(0)
        stopReason.set("active")
        maxWriteAheadFrames.set(0)
    }

    fun transmissionEnded(
        playbackActive: Boolean,
        onPlaybackDrained: () -> Unit,
    ): Boolean {
        incomingTransmissionActive.set(false)
        val generation = queueState.transmissionEnded()
        return if (generation != null && (queueState.hasQueuedFrames() || playbackActive)) {
            if (queue.trySend(PlaybackEvent.End(generation, onPlaybackDrained)).isSuccess) {
                false
            } else {
                queueState.generationFinished(generation)
                true
            }
        } else {
            generation?.let(queueState::generationFinished)
            true
        }
    }

    fun enqueue(burstId: String, message: ByteArray): Boolean {
        val generation = queueState.reserveFrame()
        if (generation == null) {
            if (queueState.snapshot().queuedFrames >= queueState.snapshot().maxQueuedFrames) {
                recordDiagnostic("playback hard_limit frames=${queueState.snapshot().maxQueuedFrames}")
            }
            return false
        }
        if (queue.trySend(PlaybackEvent.Frame(generation, burstId, message)).isFailure) {
            queueState.frameConsumed(generation)
            return false
        }
        recordDelay(firstFrameDelayMs, generation)
        return true
    }

    fun enqueueLoss(burstId: String, frameCount: Int): Boolean {
        if (frameCount <= 0) return false
        val generation = queueState.reserveFrame() ?: return false
        if (queue.trySend(PlaybackEvent.Loss(generation, burstId, frameCount)).isFailure) {
            queueState.frameConsumed(generation)
            return false
        }
        return true
    }

    fun stop() {
        incomingTransmissionActive.set(false)
        while (queue.tryReceive().isSuccess) {}
        queueState.clear()
    }

    fun hasQueuedFrames(): Boolean = queueState.hasQueuedFrames()

    fun backlogEmpty(): Boolean {
        if (queueState.hasQueuedFrames()) return false
        val track = activeTrack.get() ?: return true
        val written = activeFramesWritten.get()
        val head = runCatching {
            track.playbackHeadPosition.toLong() and 0xffffffffL
        }.getOrDefault(written)
        return head >= written
    }

    fun snapshot(): AudioPlaybackSnapshot {
        val queueSnapshot = queueState.snapshot()
        return AudioPlaybackSnapshot(
            queuedFrames = queueSnapshot.queuedFrames,
            maxQueuedFrames = queueSnapshot.maxQueuedFrames,
            generation = metricsGeneration.get(),
            starts = trackStarts.get(),
            stops = trackStops.get(),
            stopReason = stopReason.get(),
            maxWriteAheadMs = maxWriteAheadFrames.get() * MILLIS_PER_SECOND /
                AudioConstants.PLAYBACK_SAMPLE_RATE,
        )
    }

    fun debugReportFields(): List<String> {
        val snapshot = queueState.snapshot()
        return listOf(
            "audio.playback_queue=${snapshot.queuedFrames}/${snapshot.maxQueuedFrames}",
            "audio.playback_latency=" +
                "first_frame=${metricValue(firstFrameDelayMs)}," +
                "track_start=${metricValue(trackStartDelayMs)}," +
                "first_pcm=${metricValue(firstPcmDelayMs)}",
            "audio.playback_lifecycle=" +
                "generation=${metricsGeneration.get()}," +
                "starts=${trackStarts.get()}," +
                "stops=${trackStops.get()}," +
                "last_reason=${stopReason.get()}",
            "audio.playback_write_ahead=" +
                "max=${maxWriteAheadFrames.get() * MILLIS_PER_SECOND / AudioConstants.PLAYBACK_SAMPLE_RATE}ms," +
                "limit=${PLAYBACK_WRITE_AHEAD_MS}ms",
        )
    }

    fun completeImmediately(callback: () -> Unit) {
        completeCallback(callback)
    }

    suspend fun run() {
        val minimum = AudioTrack.getMinBufferSize(
            AudioConstants.PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = voiceAudioTrackBuilder()
            .setBufferSizeInBytes(playbackBufferSizeBytes(minimum))
            .build()
        var generation: Long? = null
        var currentStopReason = "timeout"
        var trackStartedAt = 0L
        var framesWritten = 0L
        var lastWriteAtMs = 0L
        var onPlaybackDrained: (() -> Unit)? = null
        val callbacksAfterRelease = mutableListOf<() -> Unit>()
        val playbackDrainCompletion = PlaybackDrainCompletion()
        val underrunDiagnostics = PlaybackUnderrunDiagnostics()
        try {
            check(track.setPreferredDevice(routeController.preferredOutput())) { "Playback output is unavailable" }
            val capacityFrames = track.bufferCapacityInFrames
            check(capacityFrames > 0) { "Playback buffer capacity is unavailable" }
            val requestedStartThresholdFrames = playbackStartThresholdFrames(capacityFrames)
            val actualStartThresholdFrames = track.setStartThresholdInFrames(
                requestedStartThresholdFrames,
            )
            check(actualStartThresholdFrames in 1..PLAYBACK_WRITE_AHEAD_FRAMES.toInt()) {
                "Playback start threshold is unavailable: $actualStartThresholdFrames"
            }
            recordDiagnostic(
                "playback prepared capacity=$capacityFrames buffer=${track.bufferSizeInFrames} " +
                    "requested_threshold=$requestedStartThresholdFrames " +
                    "threshold=$actualStartThresholdFrames " +
                    "write_ahead_limit=${PLAYBACK_WRITE_AHEAD_MS}ms",
            )
            PlaybackStreamDecoder(
                onReset = { burstId ->
                    recordDiagnostic("playback decoder reset burst=$burstId")
                    observe(PlaybackObservation.DecoderReset(burstId))
                },
            ).use { decoder ->
                suspend fun writePcm(
                    pcm: ByteArray,
                    eventGeneration: Long,
                    eventQueueWaitMs: Long,
                ) {
                    var offset = 0
                    var queueWaitMs = eventQueueWaitMs
                    while (offset < pcm.size) {
                        val playbackHead = track.playbackHeadPosition.toLong() and 0xffffffffL
                        val requestedFrames = (pcm.size - offset) / PCM_BYTES_PER_SAMPLE
                        val writableFrames = playbackWritableFrames(
                            framesWritten = framesWritten,
                            playbackHeadFrames = playbackHead,
                            maximumAheadFrames = PLAYBACK_WRITE_AHEAD_FRAMES,
                            requestedFrames = requestedFrames,
                        )
                        if (writableFrames == 0L) {
                            delay(PLAYBACK_WRITE_AHEAD_POLL_MS)
                            continue
                        }
                        val writeStartedAtMs = SystemClock.elapsedRealtime()
                        val currentUnderruns = runCatching { track.underrunCount }
                            .getOrDefault(underrunDiagnostics.observedCount)
                        if (currentUnderruns > underrunDiagnostics.observedCount) {
                            underrunDiagnostics.observe(
                                currentCount = currentUnderruns,
                                framesWritten = framesWritten,
                                playbackHeadFrames = playbackHead,
                                queuedFrames = queueState.snapshot().queuedFrames,
                                queueWaitMs = queueWaitMs,
                                writeGapMs = writeStartedAtMs - lastWriteAtMs,
                            )?.let(recordDiagnostic)
                        }
                        val written = track.write(
                            pcm,
                            offset,
                            (writableFrames * PCM_BYTES_PER_SAMPLE).toInt(),
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (written <= 0) break
                        lastWriteAtMs = SystemClock.elapsedRealtime()
                        queueWaitMs = 0
                        offset += written
                        framesWritten += written / PCM_BYTES_PER_SAMPLE
                        activeFramesWritten.set(framesWritten)
                        val headAfterWrite = track.playbackHeadPosition.toLong() and 0xffffffffL
                        val aheadAfterWrite = (framesWritten - headAfterWrite).coerceAtLeast(0)
                        maxWriteAheadFrames.accumulateAndGet(aheadAfterWrite) { current, candidate ->
                            maxOf(current, candidate)
                        }
                        recordDelay(firstPcmDelayMs, eventGeneration)
                    }
                }
                track.play()
                check(routeController.verifyOutput { track.routedDevice }) { "Playback output did not match" }
                activeTrack.set(track)
                activeFramesWritten.set(0)
                trackStartedAt = SystemClock.elapsedRealtime()
                lastWriteAtMs = trackStartedAt
                recordDiagnostic(
                    "playback started routed=${routeController.deviceSummary(track.routedDevice)}",
                )
                while (true) {
                    val idleTimeoutMs = if (incomingTransmissionActive.get()) {
                        audioBacklogMs() + PLAYBACK_TIMEOUT_GRACE_MS
                    } else {
                        PLAYBACK_IDLE_TIMEOUT_MS
                    }
                    val queueWaitStartedAtMs = SystemClock.elapsedRealtime()
                    val event = withTimeoutOrNull(idleTimeoutMs) { queue.receive() } ?: break
                    val queueWaitMs = SystemClock.elapsedRealtime() - queueWaitStartedAtMs
                    when (event) {
                        is PlaybackEvent.Frame -> {
                            if (generation == null) {
                                generation = event.generation
                                recordTrackStarted(event.generation, trackStartedAt)
                            }
                            if (generation != event.generation) {
                                recordDiagnostic(
                                    "playback stale_frame generation=${event.generation} active=$generation",
                                )
                                queueState.frameConsumed(event.generation)
                                continue
                            }
                            var firstPcm = true
                            for (pcm in decoder.decode(event.burstId, event.message)) {
                                observe(PlaybackObservation.Pcm(event.burstId, pcm))
                                writePcm(pcm, event.generation, if (firstPcm) queueWaitMs else 0)
                                firstPcm = false
                            }
                            queueState.frameConsumed(event.generation)
                        }
                        is PlaybackEvent.Loss -> {
                            if (generation == null) {
                                generation = event.generation
                                recordTrackStarted(event.generation, trackStartedAt)
                            }
                            if (generation != event.generation) {
                                queueState.frameConsumed(event.generation)
                                continue
                            }
                            if (event.frameCount <= MAX_PLC_FRAMES) {
                                var firstPcm = true
                                for (pcm in decoder.decodeLoss(event.burstId, event.frameCount)) {
                                    observe(PlaybackObservation.Pcm(event.burstId, pcm))
                                    writePcm(pcm, event.generation, if (firstPcm) queueWaitMs else 0)
                                    firstPcm = false
                                }
                            } else {
                                decoder.resetForLongLoss(event.burstId)
                                val pcm = staticGapPcm()
                                observe(PlaybackObservation.Pcm(event.burstId, pcm))
                                writePcm(pcm, event.generation, queueWaitMs)
                            }
                            recordDiagnostic("playback loss frames=${event.frameCount}")
                            queueState.frameConsumed(event.generation)
                        }
                        is PlaybackEvent.End -> {
                            queueState.generationFinished(event.generation)
                            if (generation == null || generation == event.generation) {
                                currentStopReason = "end"
                                onPlaybackDrained = event.onPlaybackDrained
                                break
                            } else {
                                callbacksAfterRelease += event.onPlaybackDrained
                            }
                        }
                    }
                }
            }

            if (onPlaybackDrained != null) {
                playbackDrainCompletion.await {
                    awaitPlaybackDrain(track, framesWritten, underrunDiagnostics.observedCount)
                }
            }
        } finally {
            generation?.let(queueState::generationFinished)
            activeTrack.compareAndSet(track, null)
            activeFramesWritten.set(0)
            val underruns = runCatching { track.underrunCount }.getOrDefault(-1)
            runCatching { track.stop() }
            track.release()
            observeLifecycle(AudioLifecycleObservation.PlaybackReleased)
            val queued = queueState.snapshot().queuedFrames
            generation?.let { recordStopped(it, currentStopReason) }
            recordDiagnostic(
                "playback stopped generation=${generation ?: "none"} " +
                    "reason=$currentStopReason queued=$queued underruns=$underruns",
            )
            callbacksAfterRelease.forEach(::completeCallback)
            onPlaybackDrained?.let { callback ->
                playbackDrainCompletion.completeAfterRelease {
                    completeCallback(callback)
                }
            }
        }
    }

    private suspend fun awaitPlaybackDrain(
        track: AudioTrack,
        framesWritten: Long,
        observedUnderruns: Int,
    ) {
        val underrunsBefore = runCatching { track.underrunCount }.getOrDefault(observedUnderruns)
        val result = waitForPlaybackDrain(
            framesWritten = framesWritten,
            sampleRate = AudioConstants.PLAYBACK_SAMPLE_RATE,
            playbackHeadFrames = {
                track.playbackHeadPosition.toLong() and 0xffffffffL
            },
            nowMs = SystemClock::elapsedRealtime,
            wait = { delayMs -> delay(delayMs) },
        )
        val underrunsAfter = runCatching { track.underrunCount }.getOrDefault(underrunsBefore)
        recordDiagnostic(
            "playback drain frames=$framesWritten head=${result.headFrames} " +
                "completed=${result.completed} elapsed=${result.elapsedMs}ms " +
                "underruns_observed=$observedUnderruns " +
                "underruns_before=$underrunsBefore underruns_after=$underrunsAfter",
        )
    }

    private fun recordTrackStarted(generation: Long, startedAt: Long) {
        if (metricsGeneration.get() != generation) return
        trackStarts.incrementAndGet()
        recordDelay(trackStartDelayMs, generation, startedAt)
    }

    private fun recordStopped(generation: Long, reason: String) {
        if (metricsGeneration.get() != generation) return
        trackStops.incrementAndGet()
        stopReason.set(reason)
    }

    private fun recordDelay(
        target: AtomicLong,
        generation: Long,
        now: Long = SystemClock.elapsedRealtime(),
    ) {
        if (metricsGeneration.get() != generation) return
        val startedAt = transmissionStartedAt.get()
        if (startedAt <= 0) return
        target.compareAndSet(METRIC_NOT_RECORDED, (now - startedAt).coerceAtLeast(0))
    }

    private fun metricValue(metric: AtomicLong): String =
        metric.get().takeIf { it >= 0 }?.let { "${it}ms" } ?: "not_recorded"

    private fun completeCallback(callback: () -> Unit) {
        runCatching(callback).onFailure { Log.e(TAG, "playback_drained_callback_failed", it) }
    }

    private fun staticGapPcm(): ByteArray {
        val pcm = ByteArray(AudioConstants.PLAYBACK_PCM_BYTES_PER_FRAME * STATIC_GAP_FRAMES)
        var state = 0x13579BDF
        var offset = 0
        while (offset < pcm.size) {
            state = state * 1103515245 + 12345
            val sample = (((state ushr 16) and 0x7fff) % 1200 - 600).toShort()
            pcm[offset] = (sample.toInt() and 0xff).toByte()
            pcm[offset + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
            offset += 2
        }
        return pcm
    }

    private companion object {
        const val TAG = "ZenPTT.Audio"
        const val PLAYBACK_IDLE_TIMEOUT_MS = 300L
        const val PLAYBACK_TIMEOUT_GRACE_MS = 500L
        const val METRIC_NOT_RECORDED = -1L
        const val MAX_PLC_FRAMES = 3
        const val STATIC_GAP_FRAMES = 4
        const val PLAYBACK_WRITE_AHEAD_POLL_MS = 5L
    }
}

internal fun playbackBufferSizeBytes(minimumBufferBytes: Int): Int {
    return maxOf(
        minimumBufferBytes,
        (PLAYBACK_WRITE_AHEAD_FRAMES * PCM_BYTES_PER_SAMPLE).toInt(),
    )
}

internal fun playbackStartThresholdFrames(bufferCapacityFrames: Int): Int {
    require(bufferCapacityFrames > 0)
    return minOf(bufferCapacityFrames, AudioConstants.PLAYBACK_SAMPLES_PER_FRAME)
}
