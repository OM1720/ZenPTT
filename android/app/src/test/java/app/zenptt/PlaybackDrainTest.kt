package app.zenptt

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDrainTest {
    @Test
    fun unchangedUnderrunCountDoesNotCreateAnEvent() {
        val diagnostics = PlaybackUnderrunDiagnostics()

        assertNull(diagnostics.observe(0, 4_800, 960, 5, 0, 20))
        assertEquals(0, diagnostics.observedCount)
        assertEquals(0, diagnostics.recordedEvents)
    }

    @Test
    fun underrunDiagnosticsRecordGrowthAndExactTimingContext() {
        val diagnostics = PlaybackUnderrunDiagnostics()

        assertEquals(
            "playback underrun count=1 delta=1 written=4800 head=4800 ahead=0ms " +
                "queued=5 queue_wait=0ms write_gap=27ms",
            diagnostics.observe(1, 4_800, 4_800, 5, 0, 27),
        )
        assertEquals(
            "playback underrun count=4 delta=3 written=9600 head=8640 ahead=20ms " +
                "queued=1 queue_wait=35ms write_gap=41ms",
            diagnostics.observe(4, 9_600, 8_640, 1, 35, 41),
        )
        assertEquals(4, diagnostics.observedCount)
        assertEquals(2, diagnostics.recordedEvents)
    }

    @Test
    fun underrunDiagnosticsStayBoundedButPreserveTheFinalCount() {
        val diagnostics = PlaybackUnderrunDiagnostics(maxEvents = 10)

        repeat(10) { index ->
            assertNotNull(diagnostics.observe(index + 1, 0, 0, 1, 0, 20))
        }
        assertNull(diagnostics.observe(15, 0, 0, 1, 0, 20))
        assertEquals(15, diagnostics.observedCount)
        assertEquals(10, diagnostics.recordedEvents)
    }

    @Test
    fun playbackBufferCoversWriteAheadAndPreservesLargerPlatformMinimum() {
        assertEquals(9_600, playbackBufferSizeBytes(0))
        assertEquals(12_000, playbackBufferSizeBytes(12_000))
    }

    @Test
    fun playbackStartThresholdUsesOneFrameWithinWriteAheadLimit() {
        val threshold = playbackStartThresholdFrames(5_772)

        assertEquals(960, threshold)
        assertTrue(threshold > 0)
        assertTrue(threshold <= 4_800)
        assertEquals(480, playbackStartThresholdFrames(480))
    }

    @Test
    fun writeBudgetCountsTwentyAndEightyMillisecondPcm() {
        val maximum = 4_800L
        var written = 0L
        var head = 0L

        val hiss = playbackWritableFrames(written, head, maximum, requestedFrames = 3_840)
        assertEquals(3_840L, hiss)
        written += hiss
        val frame = playbackWritableFrames(written, head, maximum, requestedFrames = 960)
        assertEquals(960L, frame)
        written += frame
        assertEquals(0L, playbackWritableFrames(written, head, maximum, requestedFrames = 960))

        head += 960
        assertEquals(960L, playbackWritableFrames(written, head, maximum, requestedFrames = 3_840))
        assertTrue(written - head <= maximum)
    }

    @Test
    fun emptyPlaybackCompletesWithoutPolling() = runBlocking {
        var headReads = 0
        var clockReads = 0
        var waits = 0

        val result = waitForPlaybackDrain(
            framesWritten = 0,
            sampleRate = 48_000,
            playbackHeadFrames = { headReads++; 0 },
            nowMs = { clockReads++; 0 },
            wait = { waits++ },
        )

        assertTrue(result.completed)
        assertEquals(0L, result.elapsedMs)
        assertEquals(0, headReads)
        assertEquals(0, clockReads)
        assertEquals(0, waits)
    }

    @Test
    fun advancingHeadCompletesAtOrBeyondWrittenFrames() = runBlocking {
        var now = 0L
        var head = 0L

        val result = waitForPlaybackDrain(
            framesWritten = 250,
            sampleRate = 1_000,
            playbackHeadFrames = { head },
            nowMs = { now },
            wait = { delayMs ->
                now += delayMs
                head += 100
            },
        )

        assertTrue(result.completed)
        assertEquals(300L, result.headFrames)
        assertEquals(30L, result.elapsedMs)
        assertEquals(750L, result.timeoutMs)
    }

    @Test
    fun shortPlaybackUsesMinimumTimeout() = runBlocking {
        var now = 0L

        val result = waitForPlaybackDrain(
            framesWritten = 1,
            sampleRate = 48_000,
            playbackHeadFrames = { 0 },
            nowMs = { now },
            wait = { now += it },
        )

        assertFalse(result.completed)
        assertEquals(500L, result.elapsedMs)
        assertEquals(500L, result.timeoutMs)
    }

    @Test
    fun stalledHeadFallsBackAtFiveSecondCap() = runBlocking {
        var now = 0L

        val result = waitForPlaybackDrain(
            framesWritten = 480_000,
            sampleRate = 48_000,
            playbackHeadFrames = { 12_345 },
            nowMs = { now },
            wait = { now += it },
        )

        assertFalse(result.completed)
        assertEquals(12_345L, result.headFrames)
        assertEquals(5_000L, result.elapsedMs)
        assertEquals(5_000L, result.timeoutMs)
    }

    @Test
    fun cancellationIsNotConvertedToTimeout() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                waitForPlaybackDrain(
                    framesWritten = 1_000,
                    sampleRate = 48_000,
                    playbackHeadFrames = { 0 },
                    nowMs = { 0 },
                    wait = { throw CancellationException("test") },
                )
            }
        }
    }

    @Test
    fun successfulDrainDeliversCallbackOnceAfterRelease() = runBlocking {
        val completion = PlaybackDrainCompletion()
        var now = 0L
        var head = 0L
        completion.await {
            val result = waitForPlaybackDrain(
                framesWritten = 100,
                sampleRate = 1_000,
                playbackHeadFrames = { head },
                nowMs = { now },
                wait = {
                    now += it
                    head = 100
                },
            )
            assertTrue(result.completed)
        }

        var released = false
        var callbacks = 0
        released = true
        repeat(2) {
            completion.completeAfterRelease {
                assertTrue(released)
                callbacks++
            }
        }

        assertEquals(1, callbacks)
    }

    @Test
    fun emptyDrainDeliversCallbackOnce() = runBlocking {
        val completion = PlaybackDrainCompletion()
        completion.await {
            waitForPlaybackDrain(0, 48_000, { 0 }, { 0 }, {})
        }
        var callbacks = 0

        completion.completeAfterRelease { callbacks++ }
        completion.completeAfterRelease { callbacks++ }

        assertEquals(1, callbacks)
    }

    @Test
    fun timeoutStillDeliversCallbackOnce() = runBlocking {
        val completion = PlaybackDrainCompletion()
        var now = 0L
        completion.await {
            val result = waitForPlaybackDrain(
                framesWritten = 480_000,
                sampleRate = 48_000,
                playbackHeadFrames = { 0 },
                nowMs = { now },
                wait = { now += it },
            )
            assertFalse(result.completed)
        }
        var callbacks = 0

        completion.completeAfterRelease { callbacks++ }
        completion.completeAfterRelease { callbacks++ }

        assertEquals(1, callbacks)
    }

    @Test
    fun cancellationDoesNotAllowCallback() {
        val completion = PlaybackDrainCompletion()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                completion.await { throw CancellationException("test") }
            }
        }
        var callbacks = 0

        completion.completeAfterRelease { callbacks++ }

        assertEquals(0, callbacks)
    }

    @Test
    fun failureDoesNotAllowCallback() {
        val completion = PlaybackDrainCompletion()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                completion.await { error("test") }
            }
        }
        var callbacks = 0

        completion.completeAfterRelease { callbacks++ }

        assertEquals(0, callbacks)
    }
}
