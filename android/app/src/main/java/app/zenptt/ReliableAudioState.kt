// Process-local sender recovery and strictly ordered receive storage.
package app.zenptt

import java.util.TreeMap

internal const val AUDIO_FRAME_ACCOUNTING_BYTES = 64
internal const val MAX_BURST_DURATION_MS = 60_000L
internal const val MAX_BURST_FRAMES = 3_000L
internal const val RECEIVE_BUFFER_TARGET_MS = 100

internal data class OutgoingAudioFrame(
    val sequence: Long,
    val packet: ByteArray,
    val capturedAtMs: Long,
    var sentGeneration: Long? = null,
    var sentAtMs: Long? = null,
) {
    val accountedBytes: Int get() = packet.size + AUDIO_FRAME_ACCOUNTING_BYTES
}

internal data class OutgoingRange(
    val burstId: String,
    val firstSequence: Long,
    val packets: List<ByteArray>,
    val oldestCapturedAtMs: Long,
    val newestCapturedAtMs: Long,
    val retransmit: Boolean,
)

internal data class PendingBurstEnd(val burstId: String, val finalNextSequence: Long)

internal class OutgoingBurst(
    val burstId: String,
    val burstIndex: Long,
    val createdAtMs: Long,
) {
    private val frames = TreeMap<Long, OutgoingAudioFrame>()
    var nextSequence: Long = 0
        private set
    var finalNextSequence: Long? = null
        private set
    var endSentGeneration: Long? = null
    var releaseConfirmed: Boolean = false
    var acknowledgedNextSequence: Long = 0
        private set

    fun add(packet: ByteArray, capturedAtMs: Long): OutgoingAudioFrame {
        check(finalNextSequence == null)
        check(nextSequence < MAX_BURST_FRAMES)
        return OutgoingAudioFrame(nextSequence++, packet.copyOf(), capturedAtMs).also {
            frames[it.sequence] = it
        }
    }

    fun finish(): Long = nextSequence.also { finalNextSequence = it }

    fun acknowledge(nextSequence: Long): Boolean {
        if (nextSequence < 0 || nextSequence > this.nextSequence) return false
        if (nextSequence <= acknowledgedNextSequence) return false
        acknowledgedNextSequence = nextSequence
        frames.headMap(nextSequence, false).values.toList().forEach {
            frames.remove(it.sequence)
        }
        return true
    }

    fun reject(firstSequence: Long, nextSequence: Long, reason: String) {
        if (reason !in setOf("expired", "unknown_burst", "invalid_range")) return
        frames.subMap(firstSequence, true, nextSequence, false).values.toList().forEach {
            frames.remove(it.sequence)
        }
        if (reason == "unknown_burst") frames.clear()
    }

    fun expire(nowMs: Long, maximumAgeMs: Long) {
        frames.values.filter { nowMs - it.capturedAtMs > maximumAgeMs }.forEach {
            frames.remove(it.sequence)
        }
    }

    fun rangeForGeneration(
        generation: Long,
        maxFrames: Int = AudioFrameCodec.MAX_FRAMES_PER_MESSAGE,
    ): List<OutgoingAudioFrame> {
        val first = frames.values.firstOrNull { it.sentGeneration != generation } ?: return emptyList()
        val result = mutableListOf(first)
        var messageBytes = AudioFrameCodec.HEADER_SIZE + Short.SIZE_BYTES + first.packet.size
        var sequence = first.sequence + 1
        while (result.size < maxFrames) {
            val frame = frames[sequence] ?: break
            if (frame.sentGeneration == generation) break
            if (!AudioFrameCodec.canAppendPacket(messageBytes, result.size, frame.packet)) break
            result += frame
            messageBytes += Short.SIZE_BYTES + frame.packet.size
            sequence += 1
        }
        return result
    }

    fun hasUnsentFrames(generation: Long): Boolean =
        frames.values.any { it.sentGeneration != generation }

    fun markSent(frames: List<OutgoingAudioFrame>, generation: Long, nowMs: Long) {
        frames.forEach {
            it.sentGeneration = generation
            it.sentAtMs = nowMs
        }
    }

    fun oldestUnacknowledgedAgeMs(nowMs: Long): Long =
        frames.values.firstOrNull()?.let { (nowMs - it.capturedAtMs).coerceAtLeast(0) } ?: 0

    fun oldestSentUnacknowledgedAgeMs(generation: Long, nowMs: Long): Long? =
        frames.values.mapNotNull { frame ->
            frame.sentAtMs
                ?.takeIf { frame.sentGeneration == generation }
                ?.let { sentAtMs -> (nowMs - sentAtMs).coerceAtLeast(0) }
        }.maxOrNull()

    fun frameCount(): Int = frames.size
    fun accountedBytes(): Int = frames.values.sumOf(OutgoingAudioFrame::accountedBytes)
    fun done(): Boolean = releaseConfirmed && frames.isEmpty()
    fun remainingDurationMs(nowMs: Long): Long =
        (createdAtMs + MAX_BURST_DURATION_MS - nowMs).coerceAtLeast(0)
}

internal class OutgoingAudioStore(
    private var policy: AudioPolicy = AudioPolicy.Default,
) {
    private val bursts = linkedMapOf<String, OutgoingBurst>()
    var retransmittedFrameCount: Long = 0
        private set
    var lastAcknowledgedBurstId: String? = null
        private set
    var lastAcknowledgedNextSequence: Long = 0
        private set

    fun start(burstId: String, burstIndex: Long, nowMs: Long): OutgoingBurst =
        bursts[burstId] ?: OutgoingBurst(burstId, burstIndex, nowMs).also { bursts[burstId] = it }

    fun burst(burstId: String): OutgoingBurst? = bursts[burstId]

    fun remainingBurstDurationMs(burstId: String, nowMs: Long): Long? =
        bursts[burstId]?.remainingDurationMs(nowMs)

    fun add(burstId: String, packet: ByteArray, capturedAtMs: Long): OutgoingAudioFrame? {
        if (frameCount() >= policy.recoveryFrames) return null
        val burst = bursts[burstId] ?: return null
        if (burst.nextSequence >= MAX_BURST_FRAMES) return null
        return burst.add(packet, capturedAtMs)
    }

    fun finish(burstId: String): Long? = bursts[burstId]?.finish()

    fun pendingEnds(generation: Long): List<PendingBurstEnd> = bursts.values.mapNotNull { burst ->
        val final = burst.finalNextSequence ?: return@mapNotNull null
        if (burst.endSentGeneration == generation) null else PendingBurstEnd(burst.burstId, final)
    }

    fun sendableEnds(generation: Long): List<PendingBurstEnd> = bursts.values.mapNotNull { burst ->
        val final = burst.finalNextSequence ?: return@mapNotNull null
        if (burst.endSentGeneration == generation || burst.hasUnsentFrames(generation)) {
            null
        } else {
            PendingBurstEnd(burst.burstId, final)
        }
    }

    fun markEndSent(burstId: String, generation: Long) {
        bursts[burstId]?.endSentGeneration = generation
    }

    fun nextRange(
        nowMs: Long,
        generation: Long,
        maxFrames: Int = AudioFrameCodec.MAX_FRAMES_PER_MESSAGE,
    ): OutgoingRange? {
        expire(nowMs)
        bursts.values.forEach { burst ->
            val frames = burst.rangeForGeneration(generation, maxFrames)
            if (frames.isNotEmpty()) {
                return OutgoingRange(
                    burst.burstId,
                    frames.first().sequence,
                    frames.map { it.packet.copyOf() },
                    frames.first().capturedAtMs,
                    frames.last().capturedAtMs,
                    frames.any { it.sentGeneration != null && it.sentGeneration != generation },
                )
            }
        }
        return null
    }

    fun markRangeSent(range: OutgoingRange, generation: Long, nowMs: Long) {
        val burst = bursts[range.burstId] ?: return
        val frames = burst.rangeForGeneration(generation, range.packets.size)
            .takeIf { it.firstOrNull()?.sequence == range.firstSequence } ?: return
        if (frames.size != range.packets.size) return
        retransmittedFrameCount += frames.count {
            it.sentGeneration != null && it.sentGeneration != generation
        }
        burst.markSent(frames, generation, nowMs)
    }

    fun acknowledge(burstId: String, nextSequence: Long): Boolean {
        val burst = bursts[burstId] ?: return false
        val progressed = burst.acknowledge(nextSequence)
        if (progressed) {
            lastAcknowledgedBurstId = burstId
            lastAcknowledgedNextSequence = burst.acknowledgedNextSequence
        }
        removeDone()
        return progressed
    }

    fun reject(burstId: String, firstSequence: Long, nextSequence: Long, reason: String) {
        bursts[burstId]?.reject(firstSequence, nextSequence, reason)
        if (reason == "unknown_burst") bursts[burstId]?.releaseConfirmed = true
        removeDone()
    }

    fun confirmReleased(burstId: String) {
        bursts[burstId]?.releaseConfirmed = true
        removeDone()
    }

    fun expire(nowMs: Long) {
        bursts.values.forEach { it.expire(nowMs, policy.recoveryHorizonMs.toLong()) }
        removeDone()
    }

    fun configure(value: AudioPolicy) {
        policy = value
    }

    fun oldestSentUnacknowledgedAgeMs(generation: Long, nowMs: Long): Long? =
        bursts.values.mapNotNull {
            it.oldestSentUnacknowledgedAgeMs(generation, nowMs)
        }.maxOrNull()

    fun oldestUnacknowledgedAgeMs(nowMs: Long): Long =
        bursts.values.maxOfOrNull { it.oldestUnacknowledgedAgeMs(nowMs) } ?: 0

    fun frameCount(): Int = bursts.values.sumOf(OutgoingBurst::frameCount)
    fun accountedBytes(): Int = bursts.values.sumOf(OutgoingBurst::accountedBytes)
    fun clear() {
        bursts.clear()
        retransmittedFrameCount = 0
        lastAcknowledgedBurstId = null
        lastAcknowledgedNextSequence = 0
    }

    private fun removeDone() {
        bursts.entries.removeAll { it.value.done() }
    }
}

internal data class IncomingAudioFrame(
    val sequence: Long,
    val packet: ByteArray,
    val receivedAtMs: Long,
) {
    val accountedBytes: Int get() = packet.size + AUDIO_FRAME_ACCOUNTING_BYTES
}

internal sealed interface IncomingPoll {
    data class Frame(
        val burstId: String,
        val burstIndex: Long,
        val sequence: Long,
        val packet: ByteArray,
        val receivedAtMs: Long,
        val lastWebSocketAtMs: Long,
    ) : IncomingPoll
    data class Loss(
        val burstId: String,
        val burstIndex: Long,
        val firstSequence: Long,
        val count: Int,
    ) : IncomingPoll
    data class BurstEnded(val burstId: String, val burstIndex: Long) : IncomingPoll
    data object Waiting : IncomingPoll
    data object Empty : IncomingPoll
}

internal data class ListenCursor(val burstIndex: Long, val nextSequence: Long)

internal sealed interface IncomingIngestResult {
    data object Accepted : IncomingIngestResult
    data object Rejected : IncomingIngestResult
    data class Overflow(val cursor: ListenCursor, val repeated: Boolean) : IncomingIngestResult
}

private sealed interface IncomingQueued {
    val firstSequence: Long
    val nextSequence: Long

    data class Frame(val value: IncomingAudioFrame) : IncomingQueued {
        override val firstSequence: Long get() = value.sequence
        override val nextSequence: Long get() = value.sequence + 1
    }

    data class Gap(
        override val firstSequence: Long,
        val count: Int,
    ) : IncomingQueued {
        override val nextSequence: Long get() = firstSequence + count
    }
}

private data class IncomingBurst(
    val burstId: String,
    val burstIndex: Long,
    val events: ArrayDeque<IncomingQueued> = ArrayDeque(),
    var playbackSequence: Long = 0,
    var receivedNextSequence: Long = 0,
    var finalNextSequence: Long? = null,
    var sealed: Boolean = false,
    var lastWebSocketAtMs: Long = 0,
    var playbackState: IncomingPlaybackState = IncomingPlaybackState.Buffering,
)

private enum class IncomingPlaybackState {
    Buffering,
    Active,
}

internal class IncomingAudioStore(
    private var policy: AudioPolicy = AudioPolicy.Default,
) {
    private val burstsById = mutableMapOf<String, IncomingBurst>()
    private val bursts = ArrayDeque<IncomingBurst>()
    private var incarnationId: String? = null
    private var eligibleFromIndex: Long = 0
    private var nextCursorIndex: Long = 0
    private var pendingCursor: ListenCursor? = null
    private var accountedBytes = 0
    private var overflowCursor: ListenCursor? = null
    var maxFrameCount: Int = 0
        private set

    fun initialize(channelIncarnationId: String, eligibleFromIndex: Long) {
        if (incarnationId != channelIncarnationId) {
            clear()
            incarnationId = channelIncarnationId
        }
        this.eligibleFromIndex = maxOf(this.eligibleFromIndex, eligibleFromIndex)
        nextCursorIndex = maxOf(nextCursorIndex, this.eligibleFromIndex)
        discardBefore(this.eligibleFromIndex)
    }

    fun start(burstId: String, burstIndex: Long) {
        if (burstIndex < eligibleFromIndex || burstId in burstsById) return
        if (bursts.lastOrNull()?.burstIndex?.let { burstIndex <= it } == true) return
        val initialSequence = pendingCursor
            ?.takeIf { it.burstIndex == burstIndex }
            ?.nextSequence
            ?: 0
        IncomingBurst(
            burstId,
            burstIndex,
            playbackSequence = initialSequence,
            receivedNextSequence = initialSequence,
        ).also {
            burstsById[burstId] = it
            bursts.addLast(it)
        }
        if (pendingCursor?.burstIndex == burstIndex) pendingCursor = null
    }

    fun ingest(envelope: NetworkAudioEnvelope, receivedAtMs: Long = 0): IncomingIngestResult {
        if (envelope.direction != MediaDirection.Downlink) return IncomingIngestResult.Rejected
        val burst = burstsById[envelope.burstId] ?: return IncomingIngestResult.Rejected
        if (envelope.firstSequence > burst.receivedNextSequence) return IncomingIngestResult.Rejected
        val candidates = envelope.opusPackets.mapIndexed { offset, packet ->
            IncomingAudioFrame(envelope.firstSequence + offset, packet.copyOf(), receivedAtMs)
        }
        val newFrames = candidates.filter { it.sequence >= burst.receivedNextSequence }
        candidates.filter { it.sequence in burst.playbackSequence until burst.receivedNextSequence }
            .forEach { duplicate ->
                val existing = burst.events.asSequence()
                    .filterIsInstance<IncomingQueued.Frame>()
                    .firstOrNull { it.value.sequence == duplicate.sequence }
                    ?.value
                if (existing != null && !existing.packet.contentEquals(duplicate.packet)) {
                    return IncomingIngestResult.Rejected
                }
            }
        if (newFrames.firstOrNull()?.sequence?.let { it != burst.receivedNextSequence } == true) {
            return IncomingIngestResult.Rejected
        }
        if (frameCount() + newFrames.size > policy.receiveFifoFrames) {
            val cursor = listenCursor()
            val result = IncomingIngestResult.Overflow(cursor, overflowCursor == cursor)
            overflowCursor = cursor
            return result
        }
        newFrames.forEach { candidate ->
            burst.events.addLast(IncomingQueued.Frame(candidate))
            burst.receivedNextSequence += 1
            accountedBytes += candidate.accountedBytes
        }
        burst.lastWebSocketAtMs = receivedAtMs
        maxFrameCount = maxOf(maxFrameCount, frameCount())
        return IncomingIngestResult.Accepted
    }

    fun released(burstId: String) = Unit

    fun gaps(burstId: String, ranges: List<AudioSequenceRange>) {
        val burst = burstsById[burstId] ?: return
        ranges.forEach { range ->
            if (range.count <= 0 || range.firstSequence > burst.receivedNextSequence) return@forEach
            val first = maxOf(range.firstSequence, burst.receivedNextSequence)
            val count = (range.nextSequence - first).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (count > 0) {
                burst.events.addLast(IncomingQueued.Gap(first, count))
                burst.receivedNextSequence += count
            }
        }
    }

    fun sealed(burstId: String, finalNextSequence: Long) {
        val burst = burstsById[burstId] ?: return
        burst.finalNextSequence = finalNextSequence
        burst.sealed = true
    }

    fun reset(cursor: ListenCursor) {
        overflowCursor = null
        discardBefore(cursor.burstIndex)
        val burst = bursts.firstOrNull { it.burstIndex == cursor.burstIndex }
        if (burst != null && cursor.nextSequence > burst.playbackSequence) {
            val target = cursor.nextSequence
            while (burst.events.firstOrNull()?.nextSequence?.let { it <= target } == true) {
                removeFirstEvent(burst)
            }
            val first = burst.events.firstOrNull()
            if (first is IncomingQueued.Gap && first.firstSequence < target) {
                burst.events.removeFirst()
                burst.events.addFirst(
                    IncomingQueued.Gap(target, (first.nextSequence - target).toInt()),
                )
            }
            burst.playbackSequence = target
            burst.receivedNextSequence = maxOf(burst.receivedNextSequence, target)
            burst.playbackState = IncomingPlaybackState.Buffering
        } else if (burst == null) {
            pendingCursor = cursor
        }
        eligibleFromIndex = maxOf(eligibleFromIndex, cursor.burstIndex)
        nextCursorIndex = maxOf(nextCursorIndex, cursor.burstIndex)
    }

    fun listenCursor(): ListenCursor {
        val burst = bursts.firstOrNull()
        return if (burst == null) pendingCursor ?: ListenCursor(nextCursorIndex, 0)
        else ListenCursor(burst.burstIndex, burst.playbackSequence)
    }

    fun burstIndex(burstId: String): Long? = burstsById[burstId]?.burstIndex

    fun poll(playbackQueueEmpty: Boolean = true): IncomingPoll {
        val burst = bursts.firstOrNull() ?: return IncomingPoll.Empty
        val final = burst.finalNextSequence
        if (burst.sealed && final != null && burst.playbackSequence >= final) {
            return IncomingPoll.BurstEnded(burst.burstId, burst.burstIndex)
        }
        if (burst.playbackState == IncomingPlaybackState.Active && playbackQueueEmpty) {
            burst.playbackState = IncomingPlaybackState.Buffering
        }
        if (burst.playbackState != IncomingPlaybackState.Active && !readyToPlay(burst)) {
            return IncomingPoll.Waiting
        }
        return when (val event = burst.events.firstOrNull()) {
            is IncomingQueued.Frame -> IncomingPoll.Frame(
                burst.burstId,
                burst.burstIndex,
                event.value.sequence,
                event.value.packet,
                event.value.receivedAtMs,
                burst.lastWebSocketAtMs,
            )
            is IncomingQueued.Gap -> IncomingPoll.Loss(
                burst.burstId,
                burst.burstIndex,
                event.firstSequence,
                event.count,
            )
            null -> IncomingPoll.Waiting
        }
    }

    fun commit(item: IncomingPoll): Boolean {
        val identity = when (item) {
            is IncomingPoll.Frame -> item.burstId to item.burstIndex
            is IncomingPoll.Loss -> item.burstId to item.burstIndex
            is IncomingPoll.BurstEnded -> item.burstId to item.burstIndex
            IncomingPoll.Waiting, IncomingPoll.Empty -> null
        } ?: return false
        val burst = burstsById[identity.first] ?: return false
        if (bursts.firstOrNull() !== burst || burst.burstIndex != identity.second) return false
        when (item) {
            is IncomingPoll.Frame -> {
                if (burst.playbackSequence != item.sequence) return false
                val event = burst.events.firstOrNull() as? IncomingQueued.Frame ?: return false
                if (event.value.sequence != item.sequence) return false
                removeFirstEvent(burst)
                burst.playbackSequence += 1
                burst.playbackState = IncomingPlaybackState.Active
            }
            is IncomingPoll.Loss -> {
                if (
                    item.count <= 0 ||
                    burst.playbackSequence != item.firstSequence ||
                    burst.events.firstOrNull() !is IncomingQueued.Gap
                ) return false
                val gap = burst.events.firstOrNull() as? IncomingQueued.Gap ?: return false
                if (gap.firstSequence != item.firstSequence || gap.count != item.count) return false
                burst.events.removeFirst()
                burst.playbackSequence += item.count
                burst.playbackState = IncomingPlaybackState.Active
            }
            is IncomingPoll.BurstEnded -> {
                val final = burst.finalNextSequence ?: return false
                if (!burst.sealed || burst.playbackSequence < final) return false
                removeBurst(burst)
            }
            IncomingPoll.Waiting, IncomingPoll.Empty -> return false
        }
        if (overflowCursor?.let { it != listenCursor() } == true) overflowCursor = null
        return true
    }

    fun isEmpty(): Boolean = bursts.isEmpty()
    fun frameCount(): Int = bursts.sumOf { burst ->
        burst.events.count { it is IncomingQueued.Frame }
    }
    fun accountedBytes(): Int = accountedBytes

    fun clear() {
        burstsById.clear()
        bursts.clear()
        accountedBytes = 0
        overflowCursor = null
        maxFrameCount = 0
        eligibleFromIndex = 0
        nextCursorIndex = 0
        pendingCursor = null
    }

    fun configure(value: AudioPolicy) {
        policy = value
    }

    private fun readyToPlay(burst: IncomingBurst): Boolean {
        if (burst.sealed) return true
        var available = 0
        for (event in burst.events) {
            available += when (event) {
                is IncomingQueued.Frame -> 1
                is IncomingQueued.Gap -> event.count
            }
            if (available * AudioConstants.FRAME_DURATION_MS >= RECEIVE_BUFFER_TARGET_MS) return true
        }
        return false
    }

    private fun discardBefore(index: Long) {
        while (bursts.firstOrNull()?.burstIndex?.let { it < index } == true) {
            removeBurst(requireNotNull(bursts.firstOrNull()))
        }
    }

    private fun removeBurst(burst: IncomingBurst) {
        burst.events.filterIsInstance<IncomingQueued.Frame>().forEach {
            accountedBytes -= it.value.accountedBytes
        }
        burstsById.remove(burst.burstId)
        bursts.remove(burst)
        nextCursorIndex = maxOf(nextCursorIndex, burst.burstIndex + 1)
    }

    private fun removeFirstEvent(burst: IncomingBurst) {
        when (val event = burst.events.removeFirst()) {
            is IncomingQueued.Frame -> accountedBytes -= event.value.accountedBytes
            is IncomingQueued.Gap -> Unit
        }
    }
}
