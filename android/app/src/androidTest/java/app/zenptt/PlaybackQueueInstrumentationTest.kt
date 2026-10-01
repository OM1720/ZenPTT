package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueueInstrumentationTest {
    @Test
    fun longEchoUsesOneTrackAndDrainsQueue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val callbackCount = AtomicInteger()
        try {
            pipeline.setSessionActive(true)
            val packet = OpusEncoder().use { encoder ->
                var encoded: ByteArray? = null
                repeat(10) {
                    if (encoded == null) encoded = encoder.encode(sinePcm()).firstOrNull()
                }
                requireNotNull(encoded)
            }
            pipeline.incomingTransmissionStarted()
            repeat(FRAME_COUNT) { pipeline.play("burst", packet) }
            val drained = CountDownLatch(1)
            pipeline.incomingTransmissionEnded {
                callbackCount.incrementAndGet()
                drained.countDown()
            }

            assertTrue(
                "playback drain callback was not called",
                drained.await(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            val snapshot = pipeline.snapshot()
            assertEquals(0, snapshot.queuedFrames)
            assertEquals(1, snapshot.playbackGeneration)
            assertEquals(1, snapshot.playbackStarts)
            assertEquals(1, snapshot.playbackStops)
            assertEquals("end", snapshot.playbackStopReason)
            assertEquals(1, callbackCount.get())
        } finally {
            val closed = CountDownLatch(1)
            pipeline.close(closed::countDown)
            assertTrue(
                "audio pipeline did not close",
                closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }
        assertEquals(1, callbackCount.get())
    }

    @Test
    fun queuedIndependentBurstsUseFreshDecoderWithoutRestartingTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        val playbackReached = CountDownLatch(1)
        val allowPlayback = CompletableDeferred<Unit>()
        try {
            pipeline.setSessionActive(true)
            pipeline.observePlayback(observations::add)
            pipeline.setPlaybackStartBarrier {
                playbackReached.countDown()
                allowPlayback.await()
            }
            val firstPackets = encodeTone(440, 2)
            val secondPackets = encodeTone(880, 2)
            val expectedFirst = OpusDecoder().use { decoder -> firstPackets.flatMap(decoder::decode) }
            val expectedSecond = OpusDecoder().use { decoder -> secondPackets.flatMap(decoder::decode) }
            pipeline.incomingTransmissionStarted()
            assertTrue(pipeline.play("first", firstPackets.first()))
            assertTrue(playbackReached.await(5, TimeUnit.SECONDS))
            assertTrue(pipeline.play("first", firstPackets.last()))
            secondPackets.forEach { assertTrue(pipeline.play("second", it)) }
            assertEquals(4, pipeline.snapshot().queuedFrames)
            assertTrue(observations.isEmpty())
            val drained = CountDownLatch(1)
            pipeline.incomingTransmissionEnded(drained::countDown)
            allowPlayback.complete(Unit)

            assertTrue(
                "playback drain callback was not called",
                drained.await(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            val snapshot = pipeline.snapshot()
            val pcm = observations.filterIsInstance<PlaybackObservation.Pcm>()
            assertEquals(1, snapshot.playbackStarts)
            assertEquals(1, snapshot.playbackStops)
            assertEquals(1, snapshot.playbackGeneration)
            assertEquals(
                listOf("first", "first", "second", "second"),
                pcm.map(PlaybackObservation.Pcm::burstId),
            )
            expectedFirst.zip(pcm.take(2)).forEach { (expected, actual) ->
                assertArrayEquals(expected, actual.bytes)
            }
            expectedSecond.zip(pcm.drop(2)).forEach { (expected, actual) ->
                assertArrayEquals(expected, actual.bytes)
            }
            assertEquals(
                listOf(
                    PlaybackObservation.Pcm("first", expectedFirst[0]),
                    PlaybackObservation.Pcm("first", expectedFirst[1]),
                    PlaybackObservation.DecoderReset("second"),
                    PlaybackObservation.Pcm("second", expectedSecond[0]),
                    PlaybackObservation.Pcm("second", expectedSecond[1]),
                ).map(::observationKey),
                observations.map(::observationKey),
            )
        } finally {
            allowPlayback.complete(Unit)
            pipeline.setPlaybackStartBarrier(null)
            val closed = CountDownLatch(1)
            pipeline.close(closed::countDown)
            assertTrue(
                "audio pipeline did not close",
                closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun continuedBurstKeepsDecoderStateWhenMoreFramesArriveDuringPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        val firstPcmObserved = CountDownLatch(1)
        val allowPlayback = CountDownLatch(1)
        val held = AtomicBoolean()
        try {
            pipeline.setSessionActive(true)
            val packets = encodeTone(440, 4)
            val expected = OpusDecoder().use { decoder -> packets.flatMap(decoder::decode) }
            pipeline.observePlayback { observation ->
                observations += observation
                if (observation is PlaybackObservation.Pcm && held.compareAndSet(false, true)) {
                    firstPcmObserved.countDown()
                    assertTrue(allowPlayback.await(5, TimeUnit.SECONDS))
                }
            }
            pipeline.incomingTransmissionStarted()
            assertTrue(pipeline.play("one", packets[0]))
            assertTrue(firstPcmObserved.await(5, TimeUnit.SECONDS))
            packets.drop(1).forEach { assertTrue(pipeline.play("one", it)) }
            val drained = CountDownLatch(1)
            pipeline.incomingTransmissionEnded(drained::countDown)
            allowPlayback.countDown()

            assertTrue(drained.await(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val pcm = observations.filterIsInstance<PlaybackObservation.Pcm>()
            assertEquals(expected.size, pcm.size)
            expected.indices.forEach { index -> assertArrayEquals(expected[index], pcm[index].bytes) }
            assertTrue(observations.none { it is PlaybackObservation.DecoderReset })
            assertEquals(listOf("one", "one", "one", "one"), pcm.map { it.burstId })
            assertEquals(1, pipeline.snapshot().playbackStarts)
            assertEquals(1, pipeline.snapshot().playbackGeneration)
        } finally {
            allowPlayback.countDown()
            pipeline.observePlayback(null)
            closeAndAwait(pipeline)
        }
    }

    @Test
    fun leadingLossAndLongLossProduceExactPcmAtConsumptionBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        try {
            pipeline.setSessionActive(true)
            pipeline.observePlayback(observations::add)
            val firstPacket = encodeTone(440, 1).single()
            val secondPackets = encodeTone(880, 2)
            val expectedFirst = OpusDecoder().use { it.decode(firstPacket).single() }
            val expectedSecond = OpusDecoder().use { decoder ->
                decoder.decodeLoss(2) + decoder.decode(secondPackets[0])
            }
            val expectedAfterLongLoss = OpusDecoder().use { it.decode(secondPackets[1]).single() }
            pipeline.incomingTransmissionStarted()
            assertTrue(pipeline.play("first", firstPacket))
            assertTrue(pipeline.playLoss("second", 2))
            assertTrue(pipeline.play("second", secondPackets[0]))
            assertTrue(pipeline.playLoss("second", 4))
            assertTrue(pipeline.play("second", secondPackets[1]))
            val drained = CountDownLatch(1)
            pipeline.incomingTransmissionEnded(drained::countDown)

            assertTrue(drained.await(PLAYBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(
                listOf(
                    "pcm" to "first",
                    "reset" to "second",
                    "pcm" to "second",
                    "pcm" to "second",
                    "pcm" to "second",
                    "reset" to "second",
                    "pcm" to "second",
                    "pcm" to "second",
                ),
                observations.map(::observationKey),
            )
            val pcm = observations.filterIsInstance<PlaybackObservation.Pcm>()
            val expected = listOf(expectedFirst) + expectedSecond +
                listOf(staticGapPcmForTest(), expectedAfterLongLoss)
            assertEquals(expected.size, pcm.size)
            expected.indices.forEach { index -> assertArrayEquals(expected[index], pcm[index].bytes) }
            assertEquals(1, pipeline.snapshot().playbackStarts)
        } finally {
            pipeline.observePlayback(null)
            val closed = CountDownLatch(1)
            pipeline.close(closed::countDown)
            assertTrue(closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun reconnectRetainsQueuedPcmDecoderTrackAndGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val connection = InstrumentationConnection()
        var nowMs = 0L
        val viewModel = ChannelViewModel(
            InstrumentationStore(),
            connection,
            audio = pipeline,
            diagnostics = Diagnostics { nowMs },
            nowMs = { nowMs },
        )
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        val firstPcmObserved = CountDownLatch(1)
        val allowPlayback = CountDownLatch(1)
        val held = AtomicBoolean()
        try {
            pipeline.observePlayback { observation ->
                observations += observation
                if (observation is PlaybackObservation.Pcm && held.compareAndSet(false, true)) {
                    firstPcmObserved.countDown()
                    assertTrue(allowPlayback.await(5, TimeUnit.SECONDS))
                }
            }
            val packets = encodeTone(440, 6)
            val expected = OpusDecoder().use { decoder -> packets.flatMap(decoder::decode) }
            viewModel.setChannelCode("ROOM1")
            viewModel.connect()
            viewModel.onControl(instrumentationSnapshot(generation = 1))
            viewModel.onControl(ControlEvent.BurstStarted(FIRST_BURST, 0))
            viewModel.onAudio(downlink(FIRST_BURST, 0, packets.take(5)))
            assertTrue(firstPcmObserved.await(5, TimeUnit.SECONDS))
            waitUntil { pipeline.snapshot().queuedFrames == 5 }

            nowMs = 500
            viewModel.onReconnecting(1)
            viewModel.onControl(instrumentationSnapshot(generation = 2, nextBurstIndex = 1))
            viewModel.onAudio(downlink(FIRST_BURST, 0, packets))
            viewModel.onControl(ControlEvent.BurstSealed(FIRST_BURST, 0, 6, "complete"))
            allowPlayback.countDown()

            waitUntil(PLAYBACK_TIMEOUT_MS) {
                pipeline.snapshot().queuedFrames == 0 && pipeline.snapshot().playbackStops == 1
            }
            val pcm = observations.filterIsInstance<PlaybackObservation.Pcm>()
            assertEquals(expected.size, pcm.size)
            expected.indices.forEach { index -> assertArrayEquals(expected[index], pcm[index].bytes) }
            assertEquals(List(6) { FIRST_BURST }, pcm.map { it.burstId })
            assertTrue(observations.none { it is PlaybackObservation.DecoderReset })
            assertEquals(1, pipeline.snapshot().playbackStarts)
            assertEquals(1, pipeline.snapshot().playbackStops)
            assertEquals(1, pipeline.snapshot().playbackGeneration)
            assertEquals(1, connection.connectCount)
        } finally {
            allowPlayback.countDown()
            pipeline.observePlayback(null)
            viewModel.close()
            runBlocking { pipeline.awaitCleanup() }
        }
    }

    @Test
    fun emptyBurstBetweenNonemptyBurstsDoesNotCreatePlaybackEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = AudioPipeline(context)
        val viewModel = ChannelViewModel(
            InstrumentationStore(),
            InstrumentationConnection(),
            audio = pipeline,
            diagnostics = Diagnostics { 0 },
            nowMs = { 0 },
        )
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        val playbackReached = CountDownLatch(1)
        val allowPlayback = CompletableDeferred<Unit>()
        try {
            pipeline.observePlayback(observations::add)
            pipeline.setPlaybackStartBarrier {
                playbackReached.countDown()
                allowPlayback.await()
            }
            val firstPackets = encodeTone(440, 5)
            val thirdPackets = encodeTone(880, 2)
            val expectedFirst = OpusDecoder().use { it.decodeAll(firstPackets) }
            val expectedThird = OpusDecoder().use { it.decodeAll(thirdPackets) }
            viewModel.setChannelCode("ROOM1")
            viewModel.connect()
            viewModel.onControl(instrumentationSnapshot())
            viewModel.onControl(ControlEvent.BurstStarted(FIRST_BURST, 0))
            viewModel.onAudio(downlink(FIRST_BURST, 0, firstPackets))
            viewModel.onControl(ControlEvent.BurstSealed(FIRST_BURST, 0, 5, "complete"))
            assertTrue(playbackReached.await(5, TimeUnit.SECONDS))
            waitUntil { pipeline.snapshot().queuedFrames == 5 }
            viewModel.onControl(ControlEvent.BurstStarted(EMPTY_BURST, 1))
            viewModel.onControl(ControlEvent.BurstSealed(EMPTY_BURST, 1, 0, "complete"))
            viewModel.onControl(ControlEvent.BurstStarted(THIRD_BURST, 2))
            viewModel.onAudio(downlink(THIRD_BURST, 0, thirdPackets))
            viewModel.onControl(ControlEvent.BurstSealed(THIRD_BURST, 2, 2, "complete"))
            assertTrue(observations.isEmpty())
            allowPlayback.complete(Unit)

            waitUntil(PLAYBACK_TIMEOUT_MS) {
                pipeline.snapshot().queuedFrames == 0 && pipeline.snapshot().playbackStops == 1
            }
            val pcm = observations.filterIsInstance<PlaybackObservation.Pcm>()
            val expected = expectedFirst + expectedThird
            assertEquals(expected.size, pcm.size)
            expected.indices.forEach { index -> assertArrayEquals(expected[index], pcm[index].bytes) }
            assertEquals(
                List(5) { FIRST_BURST } + List(2) { THIRD_BURST },
                pcm.map { it.burstId },
            )
            assertTrue(observations.none {
                it is PlaybackObservation.Pcm && it.burstId == EMPTY_BURST
            })
            assertEquals(listOf(THIRD_BURST), observations.filterIsInstance<PlaybackObservation.DecoderReset>().map { it.burstId })
            assertEquals(1, pipeline.snapshot().playbackStarts)
            assertEquals(1, pipeline.snapshot().playbackGeneration)
        } finally {
            allowPlayback.complete(Unit)
            pipeline.setPlaybackStartBarrier(null)
            pipeline.observePlayback(null)
            viewModel.close()
            runBlocking { pipeline.awaitCleanup() }
        }
    }

    private fun closeAndAwait(pipeline: AudioPipeline) {
        val closed = CountDownLatch(1)
        pipeline.close(closed::countDown)
        assertTrue(closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        runBlocking { pipeline.awaitCleanup() }
    }

    private fun staticGapPcmForTest(): ByteArray {
        val pcm = ByteArray(AudioConstants.PLAYBACK_PCM_BYTES_PER_FRAME * 4)
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

    private fun sinePcm(): ByteArray {
        val buffer = ByteBuffer.allocate(AudioConstants.PCM_BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(AudioConstants.SAMPLES_PER_FRAME) { index ->
            val sample = (sin(2 * PI * 440 * index / AudioConstants.SAMPLE_RATE) * 10_000).toInt()
            buffer.putShort(sample.toShort())
        }
        return buffer.array()
    }

    private fun encodeTone(frequency: Int, frameCount: Int): List<ByteArray> {
        val buffer = ByteBuffer.allocate(AudioConstants.PCM_BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(AudioConstants.SAMPLES_PER_FRAME) { index ->
            val sample = (sin(2 * PI * frequency * index / AudioConstants.SAMPLE_RATE) * 10_000).toInt()
            buffer.putShort(sample.toShort())
        }
        return OpusEncoder().use { encoder ->
            List(frameCount) { encoder.encode(buffer.array()).single() }
        }
    }

    private fun OpusDecoder.decodeAll(packets: List<ByteArray>): List<ByteArray> =
        packets.flatMap(::decode)

    private fun downlink(burstId: String, sequence: Long, packets: List<ByteArray>): ByteArray =
        AudioFrameCodec.encode(
            NetworkAudioEnvelope(MediaDirection.Downlink, burstId, sequence, packets),
        )

    private fun instrumentationSnapshot(
        generation: Int = 1,
        nextBurstIndex: Long = 0,
    ) = ControlEvent.Snapshot(
        SessionSnapshot(
            channel = "ROOM1",
            memberId = "member",
            resumeToken = "token",
            generation = generation,
            channelIncarnationId = "incarnation",
            revision = 0,
            participantCount = 2,
            eligibleFromIndex = 0,
            nextBurstIndex = nextBurstIndex,
            audioPolicy = AudioPolicy.Default,
            floor = null,
        ),
    )

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private class InstrumentationStore : ConnectionPreferences {
        override fun load() = DEFAULT_SERVER_ADDRESS
        override fun save(value: String) = Unit
    }

    private class InstrumentationConnection : ConnectionClient {
        var connectCount = 0
        override fun connect(address: String, channel: String, listener: ConnectionListener) {
            connectCount += 1
        }
        override fun listen(burstIndex: Long, nextSequence: Long) = true
        override fun sendAudio(message: ByteArray) = true
        override fun disconnect() = Unit
    }

    private fun observationKey(observation: PlaybackObservation): Pair<String, String> =
        when (observation) {
            is PlaybackObservation.DecoderReset -> "reset" to observation.burstId
            is PlaybackObservation.Pcm -> "pcm" to observation.burstId
        }

    private companion object {
        const val FIRST_BURST = "11111111-1111-4111-8111-111111111111"
        const val EMPTY_BURST = "22222222-2222-4222-8222-222222222222"
        const val THIRD_BURST = "33333333-3333-4333-8333-333333333333"
        const val FRAME_COUNT = 121
        const val PLAYBACK_TIMEOUT_MS = 30_000L
        const val CLOSE_TIMEOUT_SECONDS = 8L
    }
}
