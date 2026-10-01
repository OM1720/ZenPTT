package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioRecoveryEndToEndTest {
    @Test
    fun realPlaybackResumesWithoutLostOrRepeatedPcmAndPlaysTheNextBurst() = runBlocking {
        val pipeline = AudioPipeline(InstrumentationRegistry.getInstrumentation().targetContext)
        val observations = CopyOnWriteArrayList<PlaybackObservation>()
        val drains = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        pipeline.observePlayback(observations::add)
        val receiving = object : AudioGate by pipeline {
            override fun close() = pipeline.close { closed.complete(Unit) }

            override fun incomingTransmissionEnded(onPlaybackDrained: () -> Unit) {
                pipeline.incomingTransmissionEnded { drains.incrementAndGet(); onPlaybackDrained() }
            }
        }
        val source = SignalAudioGate()
        val http = OkHttpClient()
        val socket = AtomicReference<WebSocket>()
        val receiverConnection = RecordedConnection(ZenWebSocketClient(
            httpClient = http,
            reconnectJitterMs = { 0 },
            webSocketFactory = { request, listener -> http.newWebSocket(request, listener).also(socket::set) },
        ))
        val senderConnection = RecordedConnection(ZenWebSocketClient())
        val store = object : ConnectionPreferences {
            override fun load() = testServerAddress()
            override fun save(value: String) = Unit
        }
        val sender = ChannelViewModel(store, senderConnection, source)
        val receiver = ChannelViewModel(store, receiverConnection, receiving)
        val room = "RECOVERY.${UUID.randomUUID().toString().replace("-", "").uppercase()}"
        try {
            for (model in listOf(receiver, sender)) {
                model.setChannelCode(room)
                model.connect()
                withTimeout(10_000) { model.state.first { it.status == SessionStatus.Ready } }
            }
            val initial = receiverConnection.controls.filterIsInstance<ControlEvent.Snapshot>().single().session
            repeat(2) { round ->
                source.prepare(round)
                val controlStart = senderConnection.controls.size
                sender.pttDown()
                if (round == 0) {
                    withTimeout(5_000) {
                        while (observations.filterIsInstance<PlaybackObservation.Pcm>().size < 10) delay(10)
                    }
                    val previous = socket.get()
                    previous.cancel()
                    withTimeout(10_000) {
                        while (receiverConnection.controls.filterIsInstance<ControlEvent.Snapshot>().size < 2) delay(10)
                    }
                    val resumed = receiverConnection.controls.filterIsInstance<ControlEvent.Snapshot>().last().session
                    assertEquals(initial.memberId, resumed.memberId)
                    assertEquals(initial.channelIncarnationId, resumed.channelIncarnationId)
                    assertTrue(resumed.generation > initial.generation)
                    assertTrue(socket.get() !== previous)
                }
                withTimeout(8_000) {
                    while (source.sent.get() < TestAudioSignal.FRAMES && source.failure == null) delay(10)
                }
                assertEquals(null, source.failure)
                sender.pttUp()
                withTimeout(10_000) {
                    while (drains.get() < round + 1 || receiver.state.value.status != SessionStatus.Ready) delay(10)
                }
                val grant = senderConnection.controls.drop(controlStart).filterIsInstance<ControlEvent.PttGranted>().single()
                val played = observations.filterIsInstance<PlaybackObservation.Pcm>().filter { it.burstId == grant.burstId }
                assertEquals(TestAudioSignal.FRAMES, played.size)
                assertArrayEquals(TestAudioSignal.decode(source.packets), played.fold(byteArrayOf()) { pcm, frame -> pcm + frame.bytes })
                assertFalse("Decoder reset within a continuous burst", observations.filterIsInstance<PlaybackObservation.DecoderReset>().any { it.burstId == grant.burstId })
                assertEquals(0, pipeline.snapshot().queuedFrames)
                assertEquals(round + 1, drains.get())
                assertFalse(receiverConnection.controls.any { it is ControlEvent.BurstGaps })
            }
        } finally {
            sender.disconnect()
            receiver.disconnect()
            source.close()
            sender.close()
            receiver.close()
            withTimeout(5_000) { closed.await() }
            assertEquals(AudioRouteStatus.Inactive, pipeline.snapshot().routeStatus)
            assertEquals(0, pipeline.snapshot().queuedFrames)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
