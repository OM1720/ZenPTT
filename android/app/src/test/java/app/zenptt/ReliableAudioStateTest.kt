package app.zenptt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliableAudioStateTest {
    @Test
    fun cumulativeAckRemovesOnlyResolvedPrefix() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        repeat(3) { store.add(BURST_1, byteArrayOf(it.toByte()), it * 20L) }
        store.start(BURST_2, 2, 60)
        store.add(BURST_2, byteArrayOf(9), 60)

        store.acknowledge(BURST_1, 2)

        assertEquals(2, store.frameCount())
        assertEquals(2L, store.nextRange(100, 1)?.firstSequence)
    }

    @Test
    fun outgoingStoreUsesHorizonFrameLimitWithoutAByteLimit() {
        val store = OutgoingAudioStore(AudioPolicy(1_000))
        store.start(BURST_1, 0, 0)

        repeat(50) { assertNotNull(store.add(BURST_1, byteArrayOf(1), it * 20L)) }
        assertNull(store.add(BURST_1, byteArrayOf(1), 1_000))
        assertEquals(50, store.frameCount())
    }

    @Test
    fun outgoingBurstNeverExceedsTheAbsoluteSixtySecondLimit() {
        val store = OutgoingAudioStore(AudioPolicy(60_000))
        store.start(BURST_1, 0, 1_000)

        repeat(MAX_BURST_FRAMES.toInt()) { sequence ->
            assertNotNull(store.add(BURST_1, byteArrayOf(1), 1_000 + sequence * 20L))
        }

        assertNull(store.add(BURST_1, byteArrayOf(2), 61_000))
        assertEquals(0L, store.remainingBurstDurationMs(BURST_1, 61_000))
        assertEquals(MAX_BURST_FRAMES, store.finish(BURST_1))
    }

    @Test
    fun rangesAreSentOncePerSocketGenerationAndReplayAfterReconnect() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        store.add(BURST_1, byteArrayOf(1), 0)
        val first = requireNotNull(store.nextRange(0, 1))
        store.markRangeSent(first, 1, 0)

        assertNull(store.nextRange(1, 1))
        assertNotNull(store.nextRange(1, 2))
    }

    @Test
    fun freshRangeLimitKeepsTheFourthFrameForTheNextMessage() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        repeat(4) { store.add(BURST_1, byteArrayOf(it.toByte()), it * 20L) }

        val first = requireNotNull(store.nextRange(60, 1, maxFrames = 3))
        assertEquals(3, first.packets.size)
        store.markRangeSent(first, 1, 60)

        val tail = requireNotNull(store.nextRange(60, 1, maxFrames = 3))
        assertEquals(3L, tail.firstSequence)
        assertEquals(1, tail.packets.size)
    }

    @Test
    fun outgoingRangeCarriesCaptureBoundsAndRetransmitState() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        store.add(BURST_1, byteArrayOf(1), 10)
        store.add(BURST_1, byteArrayOf(2), 30)

        val first = requireNotNull(store.nextRange(40, 1))
        assertEquals(10L, first.oldestCapturedAtMs)
        assertEquals(30L, first.newestCapturedAtMs)
        assertFalse(first.retransmit)
        store.markRangeSent(first, 1, 40)

        val replay = requireNotNull(store.nextRange(50, 2))
        assertEquals(10L, replay.oldestCapturedAtMs)
        assertEquals(30L, replay.newestCapturedAtMs)
        assertTrue(replay.retransmit)
    }

    @Test
    fun burstEndBecomesSendableOnlyAfterAllFramesForGeneration() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        repeat(51) { store.add(BURST_1, byteArrayOf(it.toByte()), it * 20L) }
        store.finish(BURST_1)

        assertEquals(1, store.pendingEnds(2).size)
        assertTrue(store.sendableEnds(2).isEmpty())
        val first = requireNotNull(store.nextRange(1_000, 2))
        assertEquals(50, first.packets.size)
        store.markRangeSent(first, 2, 1_000)
        assertTrue(store.sendableEnds(2).isEmpty())

        val second = requireNotNull(store.nextRange(1_001, 2))
        assertEquals(1, second.packets.size)
        store.markRangeSent(second, 2, 1_001)

        assertEquals(listOf(PendingBurstEnd(BURST_1, 51)), store.sendableEnds(2))
    }

    @Test
    fun sentAgeTracksTheOldestFrameUntilCumulativeAck() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        repeat(4) { store.add(BURST_1, ByteArray(AudioFrameCodec.MAX_OPUS_PACKET_BYTES) { it.toByte() }, 0) }
        val first = requireNotNull(store.nextRange(100, 1))
        assertEquals(3, first.packets.size)
        store.markRangeSent(first, 1, 100)
        val second = requireNotNull(store.nextRange(200, 1))
        assertEquals(1, second.packets.size)
        store.markRangeSent(second, 1, 200)

        assertEquals(3_000L, store.oldestSentUnacknowledgedAgeMs(1, 3_100))
        assertNull(store.oldestSentUnacknowledgedAgeMs(2, 3_100))

        store.acknowledge(BURST_1, 3)

        assertEquals(2_900L, store.oldestSentUnacknowledgedAgeMs(1, 3_100))
        store.acknowledge(BURST_1, 4)
        assertNull(store.oldestSentUnacknowledgedAgeMs(1, 3_100))
    }

    @Test
    fun outgoingRangesUseTheLargestPrefixThatFitsTheWireEnvelope() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        val packets = listOf(1_275, 1_275, 1_275, 239, 1).mapIndexed { index, size ->
            ByteArray(size) { index.toByte() }
        }
        packets.forEachIndexed { index, packet -> store.add(BURST_1, packet, index * 20L) }

        val first = requireNotNull(store.nextRange(100, 1))
        val firstMessage = AudioFrameCodec.encode(
            NetworkAudioEnvelope(MediaDirection.Uplink, BURST_1, first.firstSequence, first.packets),
        )
        assertEquals(AudioFrameCodec.MAX_MESSAGE_BYTES, firstMessage.size)
        assertEquals(4, first.packets.size)
        store.markRangeSent(first, 1, 100)

        val second = requireNotNull(store.nextRange(101, 1))
        assertEquals(1, second.packets.size)
        val decoded = listOf(first, second).flatMap { range ->
            val envelope = AudioFrameCodec.decode(
                AudioFrameCodec.encode(
                    NetworkAudioEnvelope(
                        MediaDirection.Uplink,
                        range.burstId,
                        range.firstSequence,
                        range.packets,
                    ),
                ),
            )
            envelope.opusPackets.mapIndexed { offset, packet ->
                Triple(envelope.burstId, envelope.firstSequence + offset, packet)
            }
        }
        val expected = packets.mapIndexed { sequence, packet -> Triple(BURST_1, sequence.toLong(), packet) }
        assertEquals(expected.map { it.first to it.second }, decoded.map { it.first to it.second })
        expected.zip(decoded).forEach { (expectedFrame, actualFrame) ->
            assertArrayEquals(expectedFrame.third, actualFrame.third)
        }
    }

    @Test
    fun outgoingRangeSendsOneAvailableFrameImmediatelyFromAckCursor() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        repeat(4) { store.add(BURST_1, byteArrayOf(it.toByte()), it * 20L) }
        store.acknowledge(BURST_1, 3)

        val range = requireNotNull(store.nextRange(61, 1))

        assertEquals(BURST_1, range.burstId)
        assertEquals(3L, range.firstSequence)
        assertEquals(1, range.packets.size)
        assertArrayEquals(byteArrayOf(3), range.packets.single())
    }

    @Test
    fun staleOutgoingFramesExpireAtFiveSeconds() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        store.add(BURST_1, byteArrayOf(1), 0)

        store.expire(5_001)

        assertEquals(0, store.frameCount())
    }

    @Test
    fun duplicateGrantDoesNotReplaceExistingOutgoingStore() {
        val store = OutgoingAudioStore()
        val original = store.start(BURST_1, 1, 0)
        store.add(BURST_1, byteArrayOf(1), 0)

        val duplicate = store.start(BURST_1, 1, 100)

        assertTrue(original === duplicate)
        assertEquals(1, store.frameCount())
    }

    @Test
    fun forcedReleaseWithoutWatermarkIsRemovedAfterFramesExpire() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        store.add(BURST_1, byteArrayOf(1), 0)
        store.confirmReleased(BURST_1)
        assertNotNull(store.burst(BURST_1))

        store.expire(5_001)

        assertNull(store.burst(BURST_1))
    }

    @Test
    fun incomingStoreCopiesPacketsAndRejectsASequenceJumpWithoutAGap() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        val later = byteArrayOf(2)
        assertEquals(IncomingIngestResult.Rejected, store.ingest(envelope(BURST_1, 1, later)))
        later[0] = 8

        assertEquals(IncomingIngestResult.Accepted, store.ingest(envelope(BURST_1, 0, byteArrayOf(1))))
        assertEquals(IncomingIngestResult.Accepted, store.ingest(envelope(BURST_1, 1, byteArrayOf(2))))
        store.sealed(BURST_1, 2)
        val first = store.poll() as IncomingPoll.Frame
        assertEquals(ListenCursor(1, 0), store.listenCursor())
        assertEquals(first, store.poll())
        assertTrue(store.commit(first))
        val second = store.poll(playbackQueueEmpty = false) as IncomingPoll.Frame
        assertTrue(store.commit(second))
        assertArrayEquals(byteArrayOf(1), first.packet)
        assertArrayEquals(byteArrayOf(2), second.packet)
        assertEquals(ListenCursor(1, 2), store.listenCursor())
    }

    @Test
    fun incomingFrameKeepsItsWebSocketArrivalTimestampUntilPlayback() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        store.ingest(envelope(BURST_1, 0, byteArrayOf(1)), receivedAtMs = 123)
        store.sealed(BURST_1, 1)

        val frame = store.poll() as IncomingPoll.Frame

        assertEquals(123L, frame.receivedAtMs)
        assertEquals(123L, frame.lastWebSocketAtMs)
    }

    @Test
    fun duplicatesAndGapsDoNotChangeTheNextAcceptedFrameTimestamp() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        store.ingest(envelope(BURST_1, 0, byteArrayOf(1)), receivedAtMs = 100)
        store.ingest(envelope(BURST_1, 0, byteArrayOf(1)), receivedAtMs = 150)
        store.gaps(BURST_1, listOf(AudioSequenceRange(1, 1)))
        store.ingest(envelope(BURST_1, 2, byteArrayOf(3)), receivedAtMs = 300)
        store.sealed(BURST_1, 3)

        val first = store.poll() as IncomingPoll.Frame
        assertEquals(100L, first.receivedAtMs)
        assertTrue(store.commit(first))
        val gap = store.poll(playbackQueueEmpty = false) as IncomingPoll.Loss
        assertTrue(store.commit(gap))
        val next = store.poll(playbackQueueEmpty = false) as IncomingPoll.Frame

        assertEquals(300L, next.receivedAtMs)
        assertEquals(300L, next.lastWebSocketAtMs)
    }

    @Test
    fun finalGapBecomesExplicitLossThenBurstEnds() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        store.gaps(BURST_1, listOf(AudioSequenceRange(0, 1)))
        store.ingest(envelope(BURST_1, 1, byteArrayOf(2)))
        store.sealed(BURST_1, 2)

        val loss = store.poll() as IncomingPoll.Loss
        assertEquals(0L, loss.firstSequence)
        assertEquals(1, loss.count)
        assertEquals(loss, store.poll())
        assertTrue(store.commit(loss))
        val frame = store.poll(playbackQueueEmpty = false) as IncomingPoll.Frame
        assertArrayEquals(byteArrayOf(2), frame.packet)
        assertTrue(store.commit(frame))
        val ended = store.poll() as IncomingPoll.BurstEnded
        assertTrue(store.commit(ended))
        assertTrue(store.isEmpty())
    }

    @Test
    fun listenResetSkipsExpiredPrefixWithoutCreatingLocalLoss() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        repeat(6) { sequence ->
            store.ingest(envelope(BURST_1, sequence.toLong(), byteArrayOf(sequence.toByte())))
        }
        store.reset(ListenCursor(1, 3))
        store.sealed(BURST_1, 6)

        assertEquals(ListenCursor(1, 3), store.listenCursor())
        assertEquals(3, store.frameCount())
        val first = store.poll() as IncomingPoll.Frame
        assertEquals(3L, first.sequence)
        assertTrue(store.commit(first))
    }

    @Test
    fun listenResetBeforeBurstStartSeedsTheOrderedReceiveCursor() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.reset(ListenCursor(1, 3))

        assertEquals(ListenCursor(1, 3), store.listenCursor())
        store.start(BURST_1, 1)
        assertEquals(IncomingIngestResult.Accepted, store.ingest(envelope(BURST_1, 3, byteArrayOf(3))))
        store.sealed(BURST_1, 4)

        val frame = store.poll() as IncomingPoll.Frame
        assertEquals(3L, frame.sequence)
    }

    @Test
    fun emptyInvalidRangeBurstIsRemovedAfterAuthoritativeTerminal() {
        val store = OutgoingAudioStore()
        store.start(BURST_1, 1, 0)
        assertEquals(0L, store.finish(BURST_1))

        store.reject(BURST_1, 0, 0, "invalid_range")
        store.confirmReleased(BURST_1)

        assertNull(store.burst(BURST_1))
    }

    @Test
    fun incomingFifoRejectsOverflowWithoutEvictingRetainedFrames() {
        val store = IncomingAudioStore(AudioPolicy(1_000))
        store.initialize("incarnation", 0)
        store.start(BURST_1, 0)
        val packets = List(50) { byteArrayOf(1) }

        repeat(60) { batch ->
            assertEquals(
                IncomingIngestResult.Accepted,
                store.ingest(
                    NetworkAudioEnvelope(
                        MediaDirection.Downlink,
                        BURST_1,
                        batch * 50L,
                        packets,
                    ),
                ),
            )
        }
        assertEquals(
            IncomingIngestResult.Overflow(ListenCursor(0, 0), repeated = false),
            store.ingest(envelope(BURST_1, 3_000, byteArrayOf(2))),
        )
        assertEquals(3_000, store.frameCount())
        assertArrayEquals(byteArrayOf(1), (store.poll() as IncomingPoll.Frame).packet)
    }

    @Test
    fun openBurstUsesTheFixedOneHundredMillisecondTarget() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        repeat(5) { sequence ->
            store.ingest(envelope(BURST_1, sequence.toLong(), byteArrayOf(sequence.toByte())))
        }
        val first = store.poll() as IncomingPoll.Frame
        assertTrue(store.commit(first))

        assertEquals(IncomingPoll.Waiting, store.poll(playbackQueueEmpty = true))
        assertEquals(100, RECEIVE_BUFFER_TARGET_MS)
    }

    @Test
    fun releasedBurstKeepsTheFixedTargetAcrossPause() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        repeat(5) { sequence ->
            store.ingest(envelope(BURST_1, sequence.toLong(), byteArrayOf(sequence.toByte())))
        }
        val first = store.poll() as IncomingPoll.Frame
        assertTrue(store.commit(first))
        store.released(BURST_1)

        assertEquals(IncomingPoll.Waiting, store.poll(playbackQueueEmpty = true))
        assertEquals(100, RECEIVE_BUFFER_TARGET_MS)
        assertEquals(IncomingIngestResult.Accepted, store.ingest(envelope(BURST_1, 5, byteArrayOf(5))))
        repeat(5) {
            val frame = store.poll(playbackQueueEmpty = false) as IncomingPoll.Frame
            assertTrue(store.commit(frame))
        }
        store.sealed(BURST_1, 6)
        val ended = store.poll() as IncomingPoll.BurstEnded
        assertTrue(store.commit(ended))

        assertEquals(100, RECEIVE_BUFFER_TARGET_MS)
        assertTrue(store.isEmpty())
    }

    @Test
    fun conflictingDuplicateIsRejectedWithoutReplacingPayload() {
        val store = IncomingAudioStore()
        store.initialize("incarnation", 1)
        store.start(BURST_1, 1)
        assertEquals(IncomingIngestResult.Accepted, store.ingest(envelope(BURST_1, 0, byteArrayOf(1))))
        assertEquals(IncomingIngestResult.Rejected, store.ingest(envelope(BURST_1, 0, byteArrayOf(2))))
        store.sealed(BURST_1, 1)
        assertArrayEquals(byteArrayOf(1), (store.poll() as IncomingPoll.Frame).packet)
    }

    @Test
    fun newServerIncarnationClearsRetainedReceiveState() {
        val store = IncomingAudioStore()
        store.initialize("old-incarnation", 1)
        store.start(BURST_1, 1)
        store.ingest(envelope(BURST_1, 0, byteArrayOf(1)))

        store.initialize("new-incarnation", 7)

        assertTrue(store.isEmpty())
        assertEquals(ListenCursor(7, 0), store.listenCursor())
        assertEquals(100, RECEIVE_BUFFER_TARGET_MS)
    }

    private fun envelope(burstId: String, first: Long, packet: ByteArray) = NetworkAudioEnvelope(
        MediaDirection.Downlink, burstId, first, listOf(packet),
    )

    private companion object {
        const val BURST_1 = "11111111-1111-4111-8111-111111111111"
        const val BURST_2 = "22222222-2222-4222-8222-222222222222"
    }
}
