package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NormalChannelEndToEndTest {
    @Test
    fun twoClientsArbitrateFloorRouteAudioAndAllowNextRequest() {
        val first = ZenWebSocketClient()
        val second = ZenWebSocketClient()
        val firstEvents = Events()
        val secondEvents = Events()
        try {
            first.connect(testServerAddress(), CHANNEL, firstEvents)
            second.connect(testServerAddress(), CHANNEL, secondEvents)
            firstEvents.awaitJoined()
            secondEvents.awaitJoined()
            assertTrue(first.listen(firstEvents.eligibleFromIndex, 0))
            assertTrue(second.listen(secondEvents.eligibleFromIndex, 0))

            assertTrue(first.requestPtt("first"))
            assertTrue(second.requestPtt("second"))
            awaitCondition {
                firstEvents.hasFloorResult("first") && secondEvents.hasFloorResult("second")
            }

            val firstGrant = firstEvents.grant("first")
            val secondGrant = secondEvents.grant("second")
            val firstGranted = firstGrant != null
            assertTrue(firstGranted.xor(secondGrant != null))
            val owner = if (firstGranted) first else second
            val grant = requireNotNull(firstGrant ?: secondGrant)
            val listenerEvents = if (firstGranted) secondEvents else firstEvents
            val frame = AudioFrameCodec.encode(
                NetworkAudioEnvelope(
                    MediaDirection.Uplink,
                    grant.burstId,
                    0,
                    listOf(byteArrayOf(1, 2, 3)),
                ),
            )

            assertTrue(owner.sendAudio(frame))
            assertTrue(listenerEvents.audioReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(listenerEvents.audio.any { message ->
                val envelope = AudioFrameCodec.decode(message, MediaDirection.Downlink)
                envelope.burstId == grant.burstId &&
                    envelope.firstSequence == 0L &&
                    envelope.opusPackets.single().contentEquals(byteArrayOf(1, 2, 3))
            })

            awaitCondition {
                (if (firstGranted) firstEvents else secondEvents).controls.any {
                    it is ControlEvent.UplinkAck && it.burstId == grant.burstId && it.nextSequence == 1L
                }
            }
            assertTrue(owner.finishBurst(grant.burstId, 1))
            awaitCondition {
                listenerEvents.controls.any {
                    it is ControlEvent.BurstSealed && it.burstId == grant.burstId
                }
            }

            val nextRequester = if (firstGranted) second else first
            val nextEvents = if (firstGranted) secondEvents else firstEvents
            assertTrue(nextRequester.requestPtt("next"))
            awaitCondition { nextEvents.hasGrant("next") }
            val nextGrant = requireNotNull(nextEvents.grant("next"))
            assertTrue(nextRequester.finishBurst(nextGrant.burstId, 0))
        } finally {
            first.disconnect()
            second.disconnect()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("condition was not met", condition())
    }

    private class Events : ConnectionListener {
        val controls = CopyOnWriteArrayList<ControlEvent>()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val joined = CountDownLatch(1)
        val audioReceived = CountDownLatch(1)
        @Volatile var eligibleFromIndex = 0L

        override fun onControl(event: ControlEvent) {
            controls += event
            if (event is ControlEvent.Snapshot) {
                eligibleFromIndex = event.session.eligibleFromIndex
                joined.countDown()
            }
        }

        override fun onAudio(message: ByteArray) {
            audio += message
            audioReceived.countDown()
        }

        override fun onReconnecting(attempt: Int) = Unit
        override fun onConnectionError() = Unit

        fun awaitJoined() = assertTrue(joined.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        fun grant(id: String) = controls.filterIsInstance<ControlEvent.PttGranted>()
            .firstOrNull { it.requestId == id }
        fun hasGrant(id: String) = grant(id) != null
        fun hasFloorResult(id: String) = controls.any {
            it is ControlEvent.PttGranted && it.requestId == id ||
                it is ControlEvent.PttDenied && it.requestId == id
        }
    }

    private companion object {
        const val CHANNEL = "SYSTEMTEST"
        const val TIMEOUT_SECONDS = 10L
    }
}
