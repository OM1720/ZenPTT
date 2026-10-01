// Waits for queued AudioTrack data to drain with bounded timing and exactly-once completion.
package app.zenptt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

internal data class PlaybackDrainResult(
    val completed: Boolean,
    val headFrames: Long,
    val elapsedMs: Long,
    val timeoutMs: Long,
)

internal class PlaybackDrainCompletion {
    private val waitReturned = AtomicBoolean()
    private val callbackDelivered = AtomicBoolean()

    suspend fun await(waitForDrain: suspend () -> Unit) {
        waitForDrain()
        waitReturned.set(true)
    }

    fun completeAfterRelease(callback: () -> Unit) {
        if (waitReturned.get() && callbackDelivered.compareAndSet(false, true)) callback()
    }
}
internal suspend fun waitForPlaybackDrain(
    framesWritten: Long,
    sampleRate: Int,
    playbackHeadFrames: () -> Long,
    nowMs: () -> Long,
    wait: suspend (Long) -> Unit,
): PlaybackDrainResult {
    require(framesWritten >= 0)
    require(sampleRate > 0)
    if (framesWritten == 0L) return PlaybackDrainResult(true, 0, 0, 0)

    val startedAt = nowMs()
    val expectedMs = framesWritten * MILLIS_PER_SECOND / sampleRate
    val timeoutMs = (expectedMs + PLAYBACK_DRAIN_GRACE_MS)
        .coerceIn(MIN_PLAYBACK_DRAIN_TIMEOUT_MS, MAX_PLAYBACK_DRAIN_TIMEOUT_MS)
    var headFrames = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        headFrames = playbackHeadFrames()
        val elapsedMs = (nowMs() - startedAt).coerceAtLeast(0)
        if (headFrames >= framesWritten) {
            return PlaybackDrainResult(true, headFrames, elapsedMs, timeoutMs)
        }
        if (elapsedMs >= timeoutMs) {
            return PlaybackDrainResult(false, headFrames, elapsedMs, timeoutMs)
        }
        wait(minOf(PLAYBACK_DRAIN_POLL_MS, timeoutMs - elapsedMs))
    }
}

internal fun playbackWritableFrames(
    framesWritten: Long,
    playbackHeadFrames: Long,
    maximumAheadFrames: Long,
    requestedFrames: Long,
): Long {
    require(framesWritten >= 0)
    require(playbackHeadFrames >= 0)
    require(maximumAheadFrames > 0)
    require(requestedFrames >= 0)
    val aheadFrames = (framesWritten - playbackHeadFrames).coerceAtLeast(0)
    return minOf(requestedFrames, (maximumAheadFrames - aheadFrames).coerceAtLeast(0))
}

private const val PLAYBACK_DRAIN_GRACE_MS = 500L
private const val PLAYBACK_DRAIN_POLL_MS = 10L
private const val MIN_PLAYBACK_DRAIN_TIMEOUT_MS = 500L
private const val MAX_PLAYBACK_DRAIN_TIMEOUT_MS = 5_000L
private const val MILLIS_PER_SECOND = 1_000L
