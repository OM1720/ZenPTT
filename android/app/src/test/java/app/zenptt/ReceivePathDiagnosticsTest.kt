package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivePathDiagnosticsTest {
    @Test
    fun intervalsAtOrBelowThresholdDoNotCreateEvents() {
        val tracker = ReceivePathDiagnostics()
        tracker.envelopeIngested(BURST_1, 0, 2, 80, 100, 150, 200, 2)
        tracker.envelopeIngested(BURST_1, 2, 2, 80, 200, 200, 200, 4)
        tracker.playbackEnqueued(BURST_1, 0, 100, 200, 300, 3, 1)
        tracker.playbackEnqueued(BURST_1, 1, 100, 200, 400, 2, 2)

        val report = tracker.debugReport()
        assertTrue(report.contains("ws_max:100ms,fifo_max:100ms,playback_max:100ms"))
        assertTrue(report.contains("recorded:0,retained:0,suppressed:0"))
        assertTrue(report.endsWith("receive_path_events="))
    }

    @Test
    fun eventsContainExactTimingAndQueueFields() {
        val tracker = ReceivePathDiagnostics()
        tracker.envelopeIngested(BURST_1, 10, 2, 80, 100, 100, 100, 2)
        tracker.envelopeIngested(BURST_1, 12, 3, 120, 250, 270, 390, 5)
        tracker.playbackEnqueued(BURST_1, 10, 100, 250, 400, 4, 1)
        tracker.playbackEnqueued(BURST_1, 11, 250, 250, 550, 3, 2)

        val report = tracker.debugReport()
        assertTrue(report.contains("250:ws_gap burst=111111 first=12 frames=3 gap=150ms previous_bytes=80"))
        assertTrue(
            report.contains(
                "390:fifo_delay burst=111111 total=140ms lock_wait=20ms frames=3 fifo_after=5",
            ),
        )
        assertTrue(
            report.contains(
                "550:playback_gap burst=111111 sequence=11 gap=150ms " +
                    "frame_age=300ms ws_age=300ms fifo=3 queued=2",
            ),
        )
    }

    @Test
    fun firstPacketAndPlaybackOfNewBurstDoNotInheritPreviousIntervals() {
        val tracker = ReceivePathDiagnostics()
        tracker.envelopeIngested(BURST_1, 0, 1, 40, 0, 0, 0, 1)
        tracker.playbackEnqueued(BURST_1, 0, 0, 0, 0, 0, 1)
        tracker.envelopeIngested(BURST_2, 0, 1, 40, 1_000, 1_000, 1_000, 1)
        tracker.playbackEnqueued(BURST_2, 0, 1_000, 1_000, 1_000, 0, 1)

        val report = tracker.debugReport()
        assertTrue(report.contains("ws_max:0ms,fifo_max:0ms,playback_max:0ms"))
        assertFalse(report.contains("_gap burst="))
    }

    @Test
    fun perTypeLimitSuppressesFurtherEventsForTheBurst() {
        val tracker = ReceivePathDiagnostics()
        repeat(13) { index ->
            val atMs = index * 101L
            tracker.envelopeIngested(BURST_1, index.toLong(), 1, 40, atMs, atMs, atMs, 1)
        }

        val report = tracker.debugReport()
        assertTrue(report.contains("recorded:10,retained:10,suppressed:2"))
        assertEquals(10, report.substringAfter("receive_path_events=").split("ws_gap").size - 1)
    }

    @Test
    fun eventRingNeverExceedsThirtyEntries() {
        val tracker = ReceivePathDiagnostics()
        repeat(31) { index ->
            val burstId = "burst-$index"
            tracker.envelopeIngested(burstId, 0, 1, 40, 0, 0, 0, 1)
            tracker.envelopeIngested(burstId, 1, 1, 40, 101, 101, 101, 2)
            tracker.burstFinished(burstId)
        }

        val report = tracker.debugReport()
        assertTrue(report.contains("recorded:31,retained:30,suppressed:0"))
        assertEquals(30, report.substringAfter("receive_path_events=").split("ws_gap").size - 1)
    }

    private companion object {
        const val BURST_1 = "11111111-1111-4111-8111-111111111111"
        const val BURST_2 = "22222222-2222-4222-8222-222222222222"
    }
}
