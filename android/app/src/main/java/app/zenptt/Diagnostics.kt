// Collects bounded PTT, connection, latency, sequence, and audio-queue reliability diagnostics.
package app.zenptt

import android.os.SystemClock
import java.util.ArrayDeque

class Diagnostics(private val nowMs: () -> Long = SystemClock::elapsedRealtime) {
    internal fun monotonicNowMs(): Long = nowMs()

    private val receivePath = ReceivePathDiagnostics()
    private val sourceSend = SourceSendDiagnostics()

    private var pttPressedAt: Long? = null
    private var pttRequestedAt: Long? = null
    private var pttGrantedAt: Long? = null
    private var lastSequence: Long? = null
    private var lastBurstIndex: Long? = null
    private var outgoingBacklogActive = false

    private val pttEvents = ArrayDeque<String>()
    private val recoveryEvents = ArrayDeque<String>()
    private var pttPresses = 0L
    private var pttRequests = 0L
    private var pttGrants = 0L
    private var pttDenials = 0L
    private var pttTimeouts = 0L
    private var pttSendFailures = 0L
    private var pttReleaseFailures = 0L
    private var pttLateGrants = 0L
    private var pttNoRequestPresses = 0L
    private var connectionJoins = 0L
    private var reconnectAttempts = 0L
    private var lastJoinedAtMs: Long? = null
    private var lastPongAtMs: Long? = null
    private var lastReconnectAttempt: Int? = null
    private var lastConnectionDetail: String? = null
    private var lastDisconnectedAtMs: Long? = null
    private var lastDisconnectReason: String? = null
    private var serverErrors = 0L
    private var lastServerErrorCode: String? = null
    private var lastServerErrorMessage: String? = null
    private var backfillSessions = 0L
    private var currentBackfillCursor: ListenCursor? = null
    private var currentBackfillFrames = 0L
    private var currentBackfillBytes = 0L
    private var lastBackfillCursor: ListenCursor? = null
    private var lastBackfillFrames = 0L
    private var lastBackfillBytes = 0L
    private var maxBackfillFrames = 0L
    private var maxBackfillBytes = 0L
    var lastRttMs: Long? = null
        private set
    var lastPttGrantMs: Long? = null
        private set
    var sequenceGaps: Long = 0
        private set
    var recentSequenceGaps: Long? = null
        private set

    var lastCaptureStartMs: Long? = null
        private set
    var lastFirstAudioMs: Long? = null
        private set
    var maxOutgoingQueueBytes: Long = 0
        private set
    var outgoingBacklogEvents: Long = 0
        private set
    var lastPttDenial: String? = null
        private set

    @Synchronized
    fun pttPressed(press: PttPressResult) {
        if (press.requestCreated) {
            pttPressedAt = nowMs()
            lastCaptureStartMs = null
            lastFirstAudioMs = null
        }
        pttPresses++
        recordPtt("press")
        if (!press.requestCreated) {
            pttNoRequestPresses++
            recordPtt("no_request:${press.before.name}->${press.after.name}")
        }
    }

    @Synchronized
    fun captureStarted() {
        pttPressedAt?.let { lastCaptureStartMs = nowMs() - it }
        pttPressedAt = null
    }

    @Synchronized
    fun connectionJoined() {
        connectionJoins++
        lastJoinedAtMs = nowMs()
    }

    @Synchronized
    fun connectionReconnecting(attempt: Int) {
        reconnectAttempts++
        lastReconnectAttempt = attempt
        recordRecovery("reconnect:attempt=$attempt")
    }

    @Synchronized
    fun connectionDiagnostic(detail: String) {
        lastConnectionDetail = sanitizeConnectionDetail(detail)
    }

    @Synchronized
    fun connectionDisconnected(detail: String) {
        val sanitized = sanitizeConnectionDetail(detail)
        lastConnectionDetail = sanitized
        lastDisconnectedAtMs = nowMs()
        lastDisconnectReason = sanitized
    }

    @Synchronized
    fun serverError(code: String, message: String) {
        serverErrors++
        lastServerErrorCode = sanitizeConnectionDetail(code)
        lastServerErrorMessage = sanitizeConnectionDetail(message)
    }

    @Synchronized
    internal fun recoveryGap(burstId: String, ranges: List<AudioSequenceRange>) {
        val summary = ranges.joinToString("+") { "${it.firstSequence}-${it.nextSequence}" }
        recordRecovery("gap:${burstId.takeLast(6)}:$summary")
    }

    @Synchronized
    internal fun recoveryRejected(
        burstId: String,
        firstSequence: Long,
        nextSequence: Long,
        reason: String,
    ) {
        recordRecovery(
            "rejected:${burstId.takeLast(6)}:$firstSequence-$nextSequence:" +
                sanitizeConnectionDetail(reason),
        )
    }

    @Synchronized
    internal fun recoveryListenReset(cursor: ListenCursor, reason: String) {
        recordRecovery(
            "listen_reset:${cursor.burstIndex}:${cursor.nextSequence}:" +
                sanitizeConnectionDetail(reason),
        )
    }

    @Synchronized
    internal fun recoveryOverflow(cursor: ListenCursor, repeated: Boolean) {
        recordRecovery(
            "receive_overflow:${cursor.burstIndex}:${cursor.nextSequence}:" +
                if (repeated) "fatal" else "resume",
        )
    }

    @Synchronized
    internal fun backfillStarted(cursor: ListenCursor) {
        finishCurrentBackfill()
        backfillSessions++
        currentBackfillCursor = cursor
        currentBackfillFrames = 0
        currentBackfillBytes = 0
        recordRecovery("backfill_start:${cursor.burstIndex}:${cursor.nextSequence}")
    }

    @Synchronized
    fun backfillReceived(frames: Int, bytes: Int) {
        if (frames <= 0 || currentBackfillCursor == null) return
        currentBackfillFrames += frames
        currentBackfillBytes += bytes
        maxBackfillFrames = maxOf(maxBackfillFrames, currentBackfillFrames)
        maxBackfillBytes = maxOf(maxBackfillBytes, currentBackfillBytes)
    }

    @Synchronized
    internal fun backfillReset() {
        finishCurrentBackfill()
        currentBackfillCursor = null
        currentBackfillFrames = 0
        currentBackfillBytes = 0
    }

    @Synchronized
    fun pongReceived(sentAtMs: Long) {
        val now = nowMs()
        lastRttMs = (now - sentAtMs).coerceAtLeast(0)
        lastPongAtMs = now
    }

    @Synchronized
    fun pttRequested(requestId: String? = null, timeoutMs: Long? = null) {
        pttRequestedAt = nowMs()
        pttRequests++
        val event = timeoutMs?.let { "request_timeout=${it}ms" } ?: "request"
        recordPtt(event, requestId)
    }

    @Synchronized
    fun pttRequestSent(requestId: String, sent: Boolean) {
        if (!sent) pttSendFailures++
        recordPtt(if (sent) "request_sent" else "request_send_failed", requestId)
    }

    @Synchronized
    fun pttGranted(requestId: String? = null) {
        pttRequestedAt?.let { lastPttGrantMs = nowMs() - it }
        lastPttDenial = null
        pttRequestedAt = null
        pttGrantedAt = nowMs()
        pttGrants++
        recordPtt("grant", requestId)
    }

    @Synchronized
    fun audioSent() {
        val grantedAt = pttGrantedAt ?: return
        lastFirstAudioMs = nowMs() - grantedAt
        pttGrantedAt = null
    }

    @Synchronized
    fun pttDenied(reason: String, requestId: String? = null) {
        pttRequestedAt = null
        pttGrantedAt = null
        lastPttDenial = reason
        pttDenials++
        recordPtt("deny:$reason", requestId)
    }

    @Synchronized
    fun pttReleased(requestId: String, sent: Boolean) {
        if (!sent) pttReleaseFailures++
        recordPtt(if (sent) "release" else "release_failed", requestId)
    }

    @Synchronized
    fun pttTimedOut(requestId: String) {
        pttTimeouts++
        recordPtt("timeout", requestId)
    }

    @Synchronized
    fun pttLateGrant(requestId: String) {
        pttLateGrants++
        recordPtt("late_grant", requestId)
    }

    fun outgoingAudioQueue(queueBytes: Long, limitExceeded: Boolean) {
        maxOutgoingQueueBytes = maxOf(maxOutgoingQueueBytes, queueBytes)
        if (limitExceeded && !outgoingBacklogActive) {
            outgoingBacklogEvents++
        }
        outgoingBacklogActive = limitExceeded
    }

    @Synchronized
    fun audioFrame(burstIndex: Long, sequence: Long) {
        selectPlaybackBurst(burstIndex)
        val expected = lastSequence?.plus(1)
        if (expected != null && sequence > expected) addSequenceGaps(sequence - expected)
        lastSequence = sequence
    }

    @Synchronized
    fun audioLoss(burstIndex: Long, firstSequence: Long, count: Int) {
        if (count <= 0) return
        selectPlaybackBurst(burstIndex)
        addSequenceGaps(count.toLong())
        lastSequence = firstSequence + count - 1
    }

    private fun selectPlaybackBurst(burstIndex: Long) {
        if (lastBurstIndex == burstIndex) return
        lastBurstIndex = burstIndex
        lastSequence = null
        recentSequenceGaps = 0
    }

    private fun addSequenceGaps(count: Long) {
        sequenceGaps += count
        recentSequenceGaps = (recentSequenceGaps ?: 0) + count
    }

    @Synchronized
    fun resetLinkMetrics() {
        lastRttMs = null
        lastPttGrantMs = null
        lastBurstIndex = null
        lastSequence = null
        recentSequenceGaps = null
    }

    @Synchronized
    fun linkMetrics(): LinkMetrics = LinkMetrics(
        rttMs = lastRttMs,
        pttGrantMs = lastPttGrantMs,
        recentSequenceGaps = recentSequenceGaps,
    )

    fun summary(): String =
        "RTT ${lastRttMs?.let { "${it}ms" } ?: "—"} · " +
            "PTT ${lastPttGrantMs?.let { "${it}ms" } ?: "—"} · Gaps $sequenceGaps"

    @Synchronized
    fun pttDebugReport(): String =
        "ptt_counts=press:$pttPresses request:$pttRequests grant:$pttGrants deny:$pttDenials " +
            "timeout:$pttTimeouts send_fail:$pttSendFailures release_fail:$pttReleaseFailures late_grant:$pttLateGrants no_request:$pttNoRequestPresses\n" +
            "ptt_events=" + pttEvents.joinToString(",")

    @Synchronized
    fun connectionDebugReport(): String {
        val now = if (lastJoinedAtMs != null || lastPongAtMs != null || lastDisconnectedAtMs != null) nowMs() else 0L
        fun age(timestamp: Long?): String = timestamp?.let { "${(now - it).coerceAtLeast(0)}ms" } ?: "none"
        return "connection_counts=join:$connectionJoins reconnect:$reconnectAttempts\n" +
            "connection_last=joined_age:${age(lastJoinedAtMs)},pong_age:${age(lastPongAtMs)}," +
            "disconnect_age:${age(lastDisconnectedAtMs)},attempt:${lastReconnectAttempt ?: 0}," +
            "disconnect_reason:${lastDisconnectReason ?: "none"}," +
            "detail:${lastConnectionDetail ?: "none"}\n" +
            "server_errors=count:$serverErrors,last_code:${lastServerErrorCode ?: "none"}," +
            "last_message:${lastServerErrorMessage ?: "none"}\n" +
            "backfill=sessions:$backfillSessions," +
            "current:${formatBackfill(currentBackfillCursor, currentBackfillFrames, currentBackfillBytes)}," +
            "last:${formatBackfill(lastBackfillCursor, lastBackfillFrames, lastBackfillBytes)}," +
            "max_frames:$maxBackfillFrames,max_bytes:$maxBackfillBytes\n" +
            "recovery_events=" + recoveryEvents.joinToString(",")
    }

    internal fun receiveEnvelopeIngested(
        burstId: String,
        firstSequence: Long,
        frameCount: Int,
        envelopeBytes: Int,
        callbackAtMs: Long,
        lockAcquiredAtMs: Long,
        ingestedAtMs: Long,
        fifoFrames: Int,
    ) = receivePath.envelopeIngested(
        burstId,
        firstSequence,
        frameCount,
        envelopeBytes,
        callbackAtMs,
        lockAcquiredAtMs,
        ingestedAtMs,
        fifoFrames,
    )

    internal fun receivePlaybackEnqueued(
        burstId: String,
        sequence: Long,
        receivedAtMs: Long,
        lastWebSocketAtMs: Long,
        enqueuedAtMs: Long,
        fifoRemaining: Int,
        playbackQueuedFrames: Int,
    ) = receivePath.playbackEnqueued(
        burstId,
        sequence,
        receivedAtMs,
        lastWebSocketAtMs,
        enqueuedAtMs,
        fifoRemaining,
        playbackQueuedFrames,
    )

    internal fun receiveBurstFinished(burstId: String) = receivePath.burstFinished(burstId)

    internal fun receivePathDebugReport(): String = receivePath.debugReport()

    internal fun sourceAudioSent(
        sent: Boolean,
        range: OutgoingRange,
        sentAtMs: Long,
        queueBytes: Long,
        generation: Long,
    ) = sourceSend.observe(
        sent,
        range.burstId,
        range.firstSequence,
        range.packets.size,
        range.oldestCapturedAtMs,
        range.newestCapturedAtMs,
        sentAtMs,
        queueBytes,
        generation,
        range.retransmit,
    )

    internal fun sourceSendDebugReport(): String = sourceSend.debugReport()

    private fun finishCurrentBackfill() {
        val cursor = currentBackfillCursor ?: return
        lastBackfillCursor = cursor
        lastBackfillFrames = currentBackfillFrames
        lastBackfillBytes = currentBackfillBytes
    }

    private fun formatBackfill(cursor: ListenCursor?, frames: Long, bytes: Long): String =
        cursor?.let { "${it.burstIndex}:${it.nextSequence}/$frames/$bytes" } ?: "none/0/0"

    private fun sanitizeConnectionDetail(detail: String): String =
        detail.lineSequence().first().replace(',', ';').take(160)

    private fun recordPtt(event: String, requestId: String? = null) {
        val suffix = requestId?.takeLast(6)?.let { ":$it" }.orEmpty()
        pttEvents.addLast("${nowMs()}:$event$suffix")
        while (pttEvents.size > MAX_PTT_EVENTS) pttEvents.removeFirst()
    }

    private fun recordRecovery(event: String) {
        recoveryEvents.addLast("${nowMs()}:$event")
        while (recoveryEvents.size > MAX_RECOVERY_EVENTS) recoveryEvents.removeFirst()
    }

    fun reliabilitySummary(): String =
        summary() +
            " | Capture ${lastCaptureStartMs?.let { "${it}ms" } ?: "-"}" +
            " | First ${lastFirstAudioMs?.let { "${it}ms" } ?: "-"}" +
            " | Queue ${maxOutgoingQueueBytes}B/${outgoingBacklogEvents}" +
            " | Denial ${lastPttDenial ?: "-"}"

    private companion object {
        const val MAX_PTT_EVENTS = 30
        const val MAX_RECOVERY_EVENTS = 50
    }
}
