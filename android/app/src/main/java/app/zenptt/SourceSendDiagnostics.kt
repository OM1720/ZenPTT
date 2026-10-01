// Measures and bounds timing-gap diagnostics for sent audio envelopes across each source burst.
package app.zenptt

import java.util.ArrayDeque

internal class SourceSendDiagnostics(
    private val thresholdMs: Long = 100,
    private val maxEventsPerBurst: Int = 10,
    private val maxEvents: Int = 30,
) {
    private val events = ArrayDeque<String>()
    private var currentBurstId: String? = null
    private var previousSentAtMs: Long? = null
    private var burstEvents = 0
    private var maxGapMs = 0L
    private var recordedEvents = 0L
    private var suppressedEvents = 0L

    @Synchronized
    fun observe(
        sent: Boolean,
        burstId: String,
        firstSequence: Long,
        frameCount: Int,
        oldestCapturedAtMs: Long,
        newestCapturedAtMs: Long,
        sentAtMs: Long,
        queueBytes: Long,
        generation: Long,
        retransmit: Boolean,
    ) {
        if (!sent) return
        if (currentBurstId != burstId) {
            currentBurstId = burstId
            previousSentAtMs = null
            burstEvents = 0
        }
        val gapMs = previousSentAtMs?.let { elapsed(sentAtMs, it) }
        if (gapMs != null) {
            maxGapMs = maxOf(maxGapMs, gapMs)
            if (gapMs > thresholdMs) {
                if (burstEvents >= maxEventsPerBurst) {
                    suppressedEvents++
                } else {
                    burstEvents++
                    recordedEvents++
                    events.addLast(
                        "$sentAtMs:source_send_gap burst=${burstId.takeLast(6)} " +
                            "first=$firstSequence frames=$frameCount gap=${gapMs}ms " +
                            "oldest_age=${elapsed(sentAtMs, oldestCapturedAtMs)}ms " +
                            "newest_age=${elapsed(sentAtMs, newestCapturedAtMs)}ms " +
                            "queue=${queueBytes}B generation=$generation retransmit=$retransmit",
                    )
                    while (events.size > maxEvents) events.removeFirst()
                }
            }
        }
        previousSentAtMs = sentAtMs
    }

    @Synchronized
    fun debugReport(): String =
        "source_send=threshold:${thresholdMs}ms,max:${maxGapMs}ms," +
            "recorded:$recordedEvents,retained:${events.size},suppressed:$suppressedEvents\n" +
            "source_send_events=" + events.joinToString(",")

    private fun elapsed(laterMs: Long, earlierMs: Long): Long =
        (laterMs - earlierMs).coerceAtLeast(0)
}
