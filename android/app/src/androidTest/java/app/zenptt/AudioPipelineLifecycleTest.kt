package app.zenptt

import android.Manifest
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AudioPipelineLifecycleTest {
    @Test
    fun reportsUserFacingRouteLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        try {
            pipeline.setSessionActive(true)
            assertNotEquals(AudioRouteStatus.Inactive, pipeline.routeStatus.value)

            pipeline.setSessionActive(false)
            assertEquals(AudioRouteStatus.Inactive, pipeline.routeStatus.value)
        } finally {
            closeAndAwait(pipeline)
        }
    }
    @Test
    fun stopReturnsImmediatelyAndSignalsOnceAfterCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val pipeline = AudioPipeline(context)
        val senderEntered = CountDownLatch(1)
        val allowSender = CountDownLatch(1)
        val lifecycle = CopyOnWriteArrayList<AudioLifecycleObservation>()
        val callbackSnapshot = AtomicReference<AudioPipelineSnapshot>()
        val callbackLifecycle = AtomicReference<List<AudioLifecycleObservation>>()
        val callbackCount = AtomicInteger()
        pipeline.observeLifecycle(lifecycle::add)
        try {
            pipeline.start {
                senderEntered.countDown()
                check(allowSender.await(5, TimeUnit.SECONDS))
                true
            }
            Thread.sleep(100)
            assertFalse("capture started before grant", pipeline.snapshot().capturing)
            pipeline.grantCapture()
            assertTrue("capture sender was not reached", senderEntered.await(5, TimeUnit.SECONDS))
            val stopped = CountDownLatch(1)
            val startedAt = SystemClock.elapsedRealtime()

            pipeline.stop {
                callbackSnapshot.set(pipeline.snapshot())
                callbackLifecycle.set(lifecycle.toList())
                callbackCount.incrementAndGet()
                stopped.countDown()
            }

            assertTrue("audio stop call blocked", SystemClock.elapsedRealtime() - startedAt < 500)
            allowSender.countDown()
            assertTrue("audio stop callback was not called", stopped.await(5, TimeUnit.SECONDS))
            assertFalse("capture was still active in stop callback", callbackSnapshot.get().capturing)
            assertTrue(
                "AudioRecord was not released before stop callback",
                AudioLifecycleObservation.CaptureReleased in callbackLifecycle.get(),
            )
            assertEquals(1, callbackCount.get())
        } finally {
            allowSender.countDown()
            pipeline.observeLifecycle(null)
            closeAndAwait(pipeline)
        }
        assertEquals(1, callbackCount.get())
    }

    @Test
    fun playbackAndIndicatorBarriersDelayGrantedCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val pipeline = AudioPipeline(context)
        val indicatorEnded = CountDownLatch(1)
        val captureActiveAtIndicatorEnd = AtomicBoolean(false)
        try {
            pipeline.setSessionActive(true)
            val packet = OpusEncoder().use { encoder ->
                var encoded: ByteArray? = null
                repeat(10) {
                    if (encoded == null) {
                        encoded = encoder.encode(ByteArray(AudioConstants.PCM_BYTES_PER_FRAME))
                            .firstOrNull()
                    }
                }
                requireNotNull(encoded)
            }
            pipeline.incomingTransmissionStarted()
            repeat(20) { pipeline.play("burst", packet) }
            pipeline.incomingTransmissionEnded {
                pipeline.playIndicator(
                    AudioIndicator.PttQueued,
                ) {
                    captureActiveAtIndicatorEnd.set(pipeline.snapshot().capturing)
                    indicatorEnded.countDown()
                }
            }
            pipeline.start { true }
            pipeline.grantCapture()

            Thread.sleep(50)
            assertFalse("capture started before indicator ended", pipeline.snapshot().capturing)
            assertTrue("indicator did not end", indicatorEnded.await(5, TimeUnit.SECONDS))
            assertFalse(
                "capture was active when the indicator completed",
                captureActiveAtIndicatorEnd.get(),
            )
            val report = pipeline.debugReport()
            assertTrue(
                "queued indicator did not start",
                report.contains("indicator started type=PttQueued"),
            )
            assertTrue(
                "queued indicator did not end",
                report.contains("indicator ended type=PttQueued"),
            )
            assertFalse(
                "queued indicator failed",
                report.contains("indicator failure type=PttQueued"),
            )
            waitUntil { pipeline.snapshot().capturing }

            val stopped = CountDownLatch(1)
            pipeline.stopCapture(stopped::countDown)
            assertTrue("capture did not stop", stopped.await(5, TimeUnit.SECONDS))
        } finally {
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun queuedCueUsesAbsoluteBoundariesAndStopsBeforeAnEighthTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        try {
            pipeline.setSessionActive(true)
            assertTrue(
                "audio route did not settle",
                waitForCondition(4_000) { pipeline.routeStatus.value != AudioRouteStatus.Preparing },
            )
            val startedAt = SystemClock.elapsedRealtime()
            pipeline.startQueuedCue()
            while (SystemClock.elapsedRealtime() - startedAt < 5_000) Thread.sleep(10)
            pipeline.stopQueuedCue()

            assertTrue(
                "seven queued taps did not start",
                waitForCondition(1_000) { queuedTapStarts(pipeline).size == 7 },
            )
            val starts = queuedTapStarts(pipeline)
            assertEquals(7, starts.size)
            val origin = queuedCueStartedAt(pipeline)
            val expected = listOf(0L, 800L, 1_600L, 2_400L, 3_200L, 4_000L, 4_800L)
            starts.zip(expected).forEach { (actual, expectedOffset) ->
                assertTrue(
                    "queued tap offset ${actual - origin}ms was not near ${expectedOffset}ms",
                    actual - origin in (expectedOffset - 200)..(expectedOffset + 200),
                )
            }

            Thread.sleep(800)
            assertEquals("queued cue played after stop", 7, queuedTapStarts(pipeline).size)
        } finally {
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun queuedCueSkipsBusyBoundaryInsteadOfAccumulatingDelayedTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        try {
            pipeline.setSessionActive(true)
            assertTrue(
                "audio route did not settle",
                waitForCondition(4_000) { pipeline.routeStatus.value != AudioRouteStatus.Preparing },
            )
            pipeline.playIndicator(AudioIndicator.TransmissionInterrupted)
            pipeline.startQueuedCue()

            assertTrue(
                "queued tap did not start after the busy boundary",
                waitForCondition(2_000) { queuedTapStarts(pipeline).isNotEmpty() },
            )
            pipeline.stopQueuedCue()
            val offset = queuedTapStarts(pipeline).single() - queuedCueStartedAt(pipeline)
            assertTrue("busy queued boundary was not skipped: ${offset}ms", offset in 600..1_100)
            Thread.sleep(900)
            assertEquals("a delayed queued tap accumulated", 1, queuedTapStarts(pipeline).size)
        } finally {
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun rejectedIndicatorWaitsForAlreadyStartedQueuedTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val rejectedEnded = CountDownLatch(1)
        try {
            pipeline.setSessionActive(true)
            assertTrue(
                "audio route did not settle",
                waitForCondition(4_000) { pipeline.routeStatus.value != AudioRouteStatus.Preparing },
            )
            pipeline.startQueuedCue()
            assertTrue(
                "queued tap did not start",
                waitForCondition(1_000) { queuedTapStarts(pipeline).isNotEmpty() },
            )

            pipeline.stopQueuedCue()
            pipeline.playIndicator(AudioIndicator.PttRejected, rejectedEnded::countDown)

            assertTrue("rejected indicator did not finish", rejectedEnded.await(4, TimeUnit.SECONDS))
            val events = pipeline.debugReport().lineSequence().toList()
            val queuedEnd = events.indexOfFirst { it.contains("indicator ended type=PttQueued") }
            val rejectedStart = events.indexOfFirst { it.contains("indicator started type=PttRejected") }
            assertTrue("queued tap did not finish", queuedEnd >= 0)
            assertTrue("rejected indicator overlapped queued tap", rejectedStart > queuedEnd)
        } finally {
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun fullAudioStopCancelsActiveQueuedCueAndFutureTaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        try {
            pipeline.setSessionActive(true)
            pipeline.startQueuedCue()
            assertTrue(
                "queued tap did not start",
                waitForCondition(1_000) { queuedTapStarts(pipeline).isNotEmpty() },
            )
            val startsBeforeStop = queuedTapStarts(pipeline).size
            val stopped = CountDownLatch(1)

            pipeline.stop(stopped::countDown)

            assertTrue("full audio stop did not finish", stopped.await(4, TimeUnit.SECONDS))
            Thread.sleep(900)
            assertEquals(
                "queued cue continued after full audio stop",
                startsBeforeStop,
                queuedTapStarts(pipeline).size,
            )
        } finally {
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun interruptedPlaybackReleasesTrackBeforeGrantedCaptureStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val pipeline = AudioPipeline(context)
        val oldDrainCallback = CountDownLatch(1)
        val lifecycle = CopyOnWriteArrayList<AudioLifecycleObservation>()
        val firstPcmObserved = CountDownLatch(1)
        val allowPlaybackCleanup = CountDownLatch(1)
        val captureStarting = CountDownLatch(1)
        val held = AtomicBoolean()
        pipeline.observeLifecycle { observation ->
            lifecycle += observation
            if (observation == AudioLifecycleObservation.CaptureStarting) {
                captureStarting.countDown()
            }
        }
        pipeline.observePlayback { observation ->
            if (observation is PlaybackObservation.Pcm && held.compareAndSet(false, true)) {
                firstPcmObserved.countDown()
                assertTrue(allowPlaybackCleanup.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            pipeline.setSessionActive(true)
            val packet = OpusEncoder().use { encoder ->
                var encoded: ByteArray? = null
                repeat(10) {
                    if (encoded == null) {
                        encoded = encoder.encode(ByteArray(AudioConstants.PCM_BYTES_PER_FRAME))
                            .firstOrNull()
                    }
                }
                requireNotNull(encoded)
            }
            pipeline.incomingTransmissionStarted()
            repeat(40) { assertTrue(pipeline.play("burst", packet)) }
            pipeline.incomingTransmissionEnded(oldDrainCallback::countDown)
            assertTrue("playback PCM was not reached", firstPcmObserved.await(5, TimeUnit.SECONDS))

            pipeline.interruptIncomingPlayback()
            pipeline.start { true }
            pipeline.grantCapture()

            assertFalse(
                "capture started while the interrupted playback job was still blocked",
                captureStarting.await(2, TimeUnit.SECONDS),
            )
            allowPlaybackCleanup.countDown()
            assertTrue(
                "capture did not reach AudioRecord start",
                captureStarting.await(5, TimeUnit.SECONDS),
            )
            val playbackReleased = lifecycle.indexOf(AudioLifecycleObservation.PlaybackReleased)
            val captureStarting = lifecycle.indexOf(AudioLifecycleObservation.CaptureStarting)
            assertTrue(
                "AudioTrack was not released before AudioRecord start: $lifecycle",
                playbackReleased >= 0 && playbackReleased < captureStarting,
            )

            val stopped = CountDownLatch(1)
            pipeline.stopCapture(stopped::countDown)
            assertTrue("capture did not stop", stopped.await(5, TimeUnit.SECONDS))
            runBlocking { pipeline.awaitCleanup() }
            assertEquals("cancelled playback invoked its stale drain callback", 1L, oldDrainCallback.count)
            assertTrue(pipeline.snapshot().playbackStops >= 1)
        } finally {
            allowPlaybackCleanup.countDown()
            pipeline.observePlayback(null)
            pipeline.observeLifecycle(null)
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun stopCaptureCancelsIndicatorBarrierWait() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val pipeline = AudioPipeline(context)
        try {
            pipeline.playIndicator(AudioIndicator.TransmissionInterrupted)
            pipeline.start { true }
            pipeline.grantCapture()

            val stopped = CountDownLatch(1)
            pipeline.stopCapture(stopped::countDown)

            assertTrue("barrier wait did not cancel", stopped.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertFalse("capture started after cancellation", pipeline.snapshot().capturing)
        } finally {
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun playbackTrackStartsWithinTheWriteAheadLimit() {
        val minimum = AudioTrack.getMinBufferSize(
            AudioConstants.PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = voiceAudioTrackBuilder()
            .setBufferSizeInBytes(maxOf(minimum, AudioConstants.PLAYBACK_PCM_BYTES_PER_FRAME * 4))
            .build()
        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            val requested = playbackStartThresholdFrames(track.bufferCapacityInFrames)
            val actual = track.setStartThresholdInFrames(requested)
            assertTrue("invalid playback threshold: $actual", actual in 1..4_800)
            track.play()
            val pcm = ByteArray(AudioConstants.PLAYBACK_PCM_BYTES_PER_FRAME * 5)
            var written = 0
            while (written < pcm.size) {
                val count = track.write(
                    pcm,
                    written,
                    pcm.size - written,
                    AudioTrack.WRITE_BLOCKING,
                )
                assertTrue("playback write failed: $count", count > 0)
                written += count
            }
            assertTrue(
                "playback head did not advance capacity=${track.bufferCapacityInFrames} threshold=$actual",
                waitForCondition { track.playbackHeadPosition > 0 },
            )
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun queuedTapStarts(pipeline: AudioPipeline): List<Long> = pipeline.debugReport()
        .lineSequence()
        .filter { it.contains("indicator started type=PttQueued") }
        .map { it.substringBefore(' ').toLong() }
        .toList()

    private fun queuedCueStartedAt(pipeline: AudioPipeline): Long = pipeline.debugReport()
        .lineSequence()
        .first { it.contains("queued_cue started") }
        .substringBefore(' ')
        .toLong()

    private fun waitUntil(condition: () -> Boolean) = assertTrue(waitForCondition(condition = condition))

    private fun closeAndAwait(pipeline: AudioPipeline) {
        val closed = CountDownLatch(1)
        pipeline.close(closed::countDown)
        assertTrue("audio pipeline did not close", closed.await(8, TimeUnit.SECONDS))
        runBlocking { pipeline.awaitCleanup() }
    }

    private fun waitForCondition(
        timeoutMs: Long = 1_000,
        condition: () -> Boolean,
    ): Boolean {
        repeat((timeoutMs / 10).toInt().coerceAtLeast(1)) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
