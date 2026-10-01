package app.zenptt

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

class HeadsetAudioPolicyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun offExplicitlySelectsPhoneInputAndOutput() = runBlocking {
        val route = AudioRouteController(context) {}
        try {
            route.setHeadsetEnabled(false)
            assertTrue(route.prepare("test"))
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route.preferredOutput()?.type)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, route.preferredInput()?.type)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                context.getSystemService(AudioManager::class.java).communicationDevice?.type)
        } finally {
            route.clear("test", AudioRouteStatus.Inactive, AudioRouteStatus.Inactive)
            route.unregister()
        }
    }

    @Test fun pendingChangeAllowsAnExistingQueuedGrantAndWaitsForItsCapture() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val pipeline = AudioPipeline(context)
        val allowed = AtomicBoolean(false)
        val applied = CountDownLatch(1)
        val captured = CountDownLatch(1)
        try {
            val change = pipeline.atRouteBoundary(allowed::get) { applied.countDown(); true }
            assertFalse(applied.await(100, TimeUnit.MILLISECONDS))
            pipeline.start({ captured.countDown(); true }, { fail("Capture failed: $it") })
            pipeline.grantCapture()
            assertTrue("queued grant deadlocked with route change", captured.await(6, TimeUnit.SECONDS))
            assertFalse(applied.await(100, TimeUnit.MILLISECONDS))
            pipeline.stopCapture { allowed.set(true) }
            assertTrue("change did not follow capture", applied.await(6, TimeUnit.SECONDS))
            runBlocking { withTimeout(6_000) { change.join() } }
        } finally { close(pipeline) }
    }

    @Test fun cancelPendingChangeDoesNotApplyItsWrite() {
        val pipeline = AudioPipeline(context)
        val allowed = AtomicBoolean(false)
        val writes = AtomicBoolean(false)
        try {
            val change = pipeline.atRouteBoundary(allowed::get) { writes.set(true); true }
            change.cancel()
            allowed.set(true)
            runBlocking { withTimeout(6_000) { change.join() } }
            assertFalse(writes.get())
        } finally { close(pipeline) }
    }

    @Test fun changeRunsAfterCurrentBurstAndClosingToneBeforeBufferedNextBurst() {
        val pipeline = AudioPipeline(context)
        val firstReady = CountDownLatch(1)
        val allowFirst = CompletableDeferred<Unit>()
        val completed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        pipeline.setPlaybackStartBarrier { firstReady.countDown(); allowFirst.await() }
        pipeline.observePlayback {
            if (it is PlaybackObservation.Pcm && it.burstId !in events) events += it.burstId
        }
        try {
            pipeline.setSessionActive(true)
            val packet = OpusEncoder().use { encoder ->
                (1..10).flatMap { encoder.encode(ByteArray(AudioConstants.PCM_BYTES_PER_FRAME)) }.first()
            }
            pipeline.incomingTransmissionStarted()
            assertTrue(pipeline.play("first", packet))
            assertTrue(firstReady.await(6, TimeUnit.SECONDS))
            pipeline.incomingTransmissionEnded {
                pipeline.playIndicator(AudioIndicator.ChannelFree) { events += "tone" }
            }
            val change = pipeline.atRouteBoundary({ true }) {
                events += "change"
                pipeline.setHeadsetEnabled(false)
                true
            }
            pipeline.incomingTransmissionStarted()
            assertTrue(pipeline.play("second", packet))
            pipeline.incomingTransmissionEnded { completed.countDown() }
            allowFirst.complete(Unit)
            assertTrue("buffered burst did not complete: $events", completed.await(10, TimeUnit.SECONDS))
            runBlocking { withTimeout(6_000) { change.join() } }
            assertEquals(listOf("first", "tone", "change", "second"), events.toList())
        } finally { allowFirst.complete(Unit); close(pipeline) }
    }

    private fun close(pipeline: AudioPipeline) {
        val closed = CountDownLatch(1)
        pipeline.close(closed::countDown)
        assertTrue(closed.await(8, TimeUnit.SECONDS))
    }
}
