// Measures and bounds diagnostic gaps across WebSocket ingest, receive FIFO, and playback enqueue.
package app.zenptt

import java.util.ArrayDeque

internal class ReceivePathDiagnostics(
    private val thresholdMs: Long = 100,
    private val maxEventsPerType: Int = 10,
    private val maxEvents: Int = 30,
) {
    private data class EnvelopeState(
        var previousWebSocketAtMs: Long? = null,
        var previousEnvelopeBytes: Int = 0,
        var webSocketEvents: Int = 0,
        var fifoEvents: Int = 0,
    )

    private data class PlaybackState(
        var previousPlaybackAtMs: Long? = null,
        var playbackEvents: Int = 0,
    )

    private val events = ArrayDeque<String>()
    private var envelopeBurstId: String? = null
    private var envelopeState = EnvelopeState()
    private var playbackBurstId: String? = null
    private var playbackState = PlaybackState()
    private var maxWebSocketGapMs = 0L
    private var maxFifoDelayMs = 0L
    private var maxPlaybackGapMs = 0L
    private var recordedEvents = 0L
    private var suppressedEvents = 0L

    @Synchronized
    fun envelopeIngested(
        burstId: String,
        firstSequence: Long,
        frameCount: Int,
        envelopeBytes: Int,
        callbackAtMs: Long,
        lockAcquiredAtMs: Long,
        ingestedAtMs: Long,
        fifoFrames: Int,
    ) {
        if (envelopeBurstId != burstId) {
            envelopeBurstId = burstId
            envelopeState = EnvelopeState()
        }
        val webSocketGapMs = envelopeState.previousWebSocketAtMs
            ?.let { elapsed(callbackAtMs, it) }
        if (webSocketGapMs != null) {
            maxWebSocketGapMs = maxOf(maxWebSocketGapMs, webSocketGapMs)
            if (webSocketGapMs > thresholdMs) {
                record(
                    envelopeState.webSocketEvents,
                    callbackAtMs,
                        "ws_gap burst=${shortBurstId(burstId)} first=$firstSequence " +
                        "frames=$frameCount gap=${webSocketGapMs}ms " +
                        "previous_bytes=${envelopeState.previousEnvelopeBytes}",
                ) { envelopeState.webSocketEvents++ }
            }
        }

        val fifoDelayMs = elapsed(ingestedAtMs, callbackAtMs)
        maxFifoDelayMs = maxOf(maxFifoDelayMs, fifoDelayMs)
        if (fifoDelayMs > thresholdMs) {
            record(
                envelopeState.fifoEvents,
                ingestedAtMs,
                "fifo_delay burst=${shortBurstId(burstId)} total=${fifoDelayMs}ms " +
                    "lock_wait=${elapsed(lockAcquiredAtMs, callbackAtMs)}ms " +
                    "frames=$frameCount fifo_after=$fifoFrames",
            ) { envelopeState.fifoEvents++ }
        }

        envelopeState.previousWebSocketAtMs = callbackAtMs
        envelopeState.previousEnvelopeBytes = envelopeBytes
    }

    @Synchronized
    fun playbackEnqueued(
        burstId: String,
        sequence: Long,
        receivedAtMs: Long,
        lastWebSocketAtMs: Long,
        enqueuedAtMs: Long,
        fifoRemaining: Int,
        playbackQueuedFrames: Int,
    ) {
        if (playbackBurstId != burstId) {
            playbackBurstId = burstId
            playbackState = PlaybackState()
        }
        val playbackGapMs = playbackState.previousPlaybackAtMs
            ?.let { elapsed(enqueuedAtMs, it) }
        if (playbackGapMs != null) {
            maxPlaybackGapMs = maxOf(maxPlaybackGapMs, playbackGapMs)
            if (playbackGapMs > thresholdMs) {
                record(
                    playbackState.playbackEvents,
                    enqueuedAtMs,
                    "playback_gap burst=${shortBurstId(burstId)} sequence=$sequence " +
                        "gap=${playbackGapMs}ms frame_age=${elapsed(enqueuedAtMs, receivedAtMs)}ms " +
                        "ws_age=${elapsed(enqueuedAtMs, lastWebSocketAtMs)}ms " +
                        "fifo=$fifoRemaining queued=$playbackQueuedFrames",
                ) { playbackState.playbackEvents++ }
            }
        }
        playbackState.previousPlaybackAtMs = enqueuedAtMs
    }

    @Synchronized
    fun burstFinished(burstId: String) {
        if (envelopeBurstId == burstId) {
            envelopeBurstId = null
            envelopeState = EnvelopeState()
        }
        if (playbackBurstId == burstId) {
            playbackBurstId = null
            playbackState = PlaybackState()
        }
    }

    @Synchronized
    fun debugReport(): String =
        "receive_path=threshold:${thresholdMs}ms,ws_max:${maxWebSocketGapMs}ms," +
            "fifo_max:${maxFifoDelayMs}ms,playback_max:${maxPlaybackGapMs}ms," +
            "recorded:$recordedEvents,retained:${events.size},suppressed:$suppressedEvents\n" +
            "receive_path_events=" + events.joinToString(",")

    private fun record(typeEvents: Int, atMs: Long, event: String, incrementType: () -> Unit) {
        if (typeEvents >= maxEventsPerType) {
            suppressedEvents++
            return
        }
        incrementType()
        recordedEvents++
        events.addLast("$atMs:$event")
        while (events.size > maxEvents) events.removeFirst()
    }

    private fun elapsed(laterMs: Long, earlierMs: Long): Long =
        (laterMs - earlierMs).coerceAtLeast(0)

    private fun shortBurstId(burstId: String): String = burstId.takeLast(6)
}
