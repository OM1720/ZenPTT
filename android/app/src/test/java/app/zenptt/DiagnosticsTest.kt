package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun measuresRttGrantAndSequenceGaps() {
        var now = 100L
        val diagnostics = Diagnostics { now }
        diagnostics.pttPressed(acceptedPress())
        diagnostics.pttRequested()
        now = 105
        diagnostics.captureStarted()
        now = 125
        diagnostics.pongReceived(100L)
        diagnostics.pttGranted()
        now = 135
        diagnostics.audioSent()
        diagnostics.outgoingAudioQueue(100, false)
        diagnostics.outgoingAudioQueue(200, true)
        diagnostics.outgoingAudioQueue(250, true)
        diagnostics.outgoingAudioQueue(50, false)
        diagnostics.outgoingAudioQueue(300, true)
        diagnostics.audioFrame(0, 10)
        diagnostics.audioFrame(0, 12)
        diagnostics.audioFrame(1, 0)
        diagnostics.pttDenied("invalid_state")

        assertEquals(25L, diagnostics.lastRttMs)
        assertEquals(25L, diagnostics.lastPttGrantMs)
        assertEquals(1L, diagnostics.sequenceGaps)
        assertEquals(5L, diagnostics.lastCaptureStartMs)
        assertEquals(10L, diagnostics.lastFirstAudioMs)
        assertEquals(300L, diagnostics.maxOutgoingQueueBytes)
        assertEquals(2L, diagnostics.outgoingBacklogEvents)
        assertEquals("invalid_state", diagnostics.lastPttDenial)

        diagnostics.pttRequested()
        diagnostics.pttGranted()

        assertEquals(null, diagnostics.lastPttDenial)
    }

    @Test
    fun exposesRecentGapsAndResetsThemPerTransmission() {
        val diagnostics = Diagnostics { 0L }
        diagnostics.audioFrame(0, 10)
        diagnostics.audioFrame(0, 13)

        assertEquals(2L, diagnostics.sequenceGaps)
        assertEquals(2L, diagnostics.linkMetrics().recentSequenceGaps)

        diagnostics.audioFrame(1, 0)

        assertEquals(2L, diagnostics.sequenceGaps)
        assertEquals(0L, diagnostics.linkMetrics().recentSequenceGaps)
    }

    @Test
    fun playbackLossUsesItsExactRangeAndSwitchesOnlyWhenPlaybackAdvances() {
        val diagnostics = Diagnostics { 0L }
        diagnostics.audioFrame(4, 7)
        diagnostics.audioLoss(4, 10, 3)

        assertEquals(3L, diagnostics.sequenceGaps)
        assertEquals(3L, diagnostics.linkMetrics().recentSequenceGaps)

        diagnostics.audioFrame(4, 13)
        diagnostics.audioLoss(4, 0xffff_ffffL - 1, 2)

        assertEquals(5L, diagnostics.sequenceGaps)
        assertEquals(5L, diagnostics.linkMetrics().recentSequenceGaps)

        diagnostics.audioFrame(5, 0)
        diagnostics.audioLoss(5, 4, 1)

        assertEquals(6L, diagnostics.sequenceGaps)
        assertEquals(1L, diagnostics.linkMetrics().recentSequenceGaps)
    }

    @Test
    fun reportsPttLifecycleWithShortRequestIds() {
        var now = 500L
        val diagnostics = Diagnostics { now++ }

        diagnostics.pttPressed(acceptedPress())
        diagnostics.pttRequested("abcdef123456")
        diagnostics.pttRequestSent("abcdef123456", true)
        diagnostics.pttGranted("abcdef123456")
        diagnostics.pttReleased("abcdef123456", false)
        diagnostics.pttTimedOut("other-654321")
        diagnostics.pttLateGrant("other-654321")
        diagnostics.pttPressed(ignoredPress())

        val report = diagnostics.pttDebugReport()
        assertTrue(report.contains("press:2 request:1 grant:1 deny:0 timeout:1"))
        assertTrue(report.contains("release_fail:1 late_grant:1 no_request:1"))
        assertTrue(report.contains("request:123456"))
        assertTrue(report.contains("late_grant:654321"))
        assertTrue(report.contains("no_request:PlayingEcho->PlayingEcho"))
        assertTrue(!report.contains("abcdef123456"))
    }

    @Test
    fun reportsConnectionLifecycle() {
        var now = 100L
        val diagnostics = Diagnostics { now }

        diagnostics.connectionJoined()
        now = 125L
        diagnostics.pongReceived(100L)
        now = 130L
        diagnostics.connectionDisconnected("WebSocket closed, code=1001\nignored")
        now = 135L
        diagnostics.connectionDiagnostic("Audio backlog, target=1000")
        diagnostics.serverError("invalid_state", "Action is not available")
        diagnostics.connectionReconnecting(2)
        now = 150L

        val report = diagnostics.connectionDebugReport()
        assertTrue(report.contains("connection_counts=join:1 reconnect:1"))
        assertTrue(report.contains("joined_age:50ms,pong_age:25ms"))
        assertTrue(report.contains("disconnect_age:20ms,attempt:2"))
        assertTrue(report.contains("disconnect_reason:WebSocket closed; code=1001"))
        assertTrue(report.contains("detail:Audio backlog; target=1000"))
        assertTrue(report.contains("server_errors=count:1,last_code:invalid_state"))
        assertTrue(report.contains("last_message:Action is not available"))
    }

    @Test
    fun ignoredPressDoesNotShiftPendingCaptureMeasurement() {
        var now = 100L
        val diagnostics = Diagnostics { now }

        diagnostics.pttPressed(acceptedPress())
        now = 102L
        diagnostics.pttPressed(ignoredPress(SessionStatus.Requesting))
        now = 105L
        diagnostics.captureStarted()

        assertEquals(5L, diagnostics.lastCaptureStartMs)
    }

    @Test
    fun ignoredPressPreservesLastCompletedLatencyMetrics() {
        var now = 0L
        val diagnostics = Diagnostics { now }
        diagnostics.pttPressed(acceptedPress())
        now = 5L
        diagnostics.captureStarted()
        now = 10L
        diagnostics.pttGranted()
        now = 15L
        diagnostics.audioSent()

        now = 20L
        diagnostics.pttPressed(ignoredPress())

        assertEquals(5L, diagnostics.lastCaptureStartMs)
        assertEquals(5L, diagnostics.lastFirstAudioMs)
    }

    @Test
    fun keepsOnlyThirtyPttEvents() {
        var now = 0L
        val diagnostics = Diagnostics { now++ }

        repeat(31) { diagnostics.pttPressed(acceptedPress()) }

        val events = diagnostics.pttDebugReport().substringAfter("ptt_events=").split(",")
        assertEquals(30, events.size)
        assertTrue(events.none { it.startsWith("0:press") })
    }

    @Test
    fun reportsBoundedRecoveryEventsWithExactServerRanges() {
        var now = 0L
        val diagnostics = Diagnostics { now++ }
        val burstId = "11111111-1111-4111-8111-111111111111"

        diagnostics.recoveryGap(burstId, listOf(AudioSequenceRange(24, 3)))
        diagnostics.recoveryRejected(burstId, 27, 30, "expired")
        diagnostics.recoveryListenReset(ListenCursor(8, 31), "expired")
        diagnostics.recoveryOverflow(ListenCursor(8, 31), repeated = false)
        diagnostics.backfillStarted(ListenCursor(8, 31))
        repeat(180) { diagnostics.backfillReceived(50, 2_000) }
        val initial = diagnostics.connectionDebugReport()
        assertTrue(initial.contains("gap:111111:24-27"))
        assertTrue(initial.contains("rejected:111111:27-30:expired"))
        assertTrue(initial.contains("listen_reset:8:31:expired"))
        assertTrue(initial.contains("backfill=sessions:1,current:8:31/9000/360000"))
        assertTrue(initial.contains("max_frames:9000,max_bytes:360000"))
        repeat(46) { diagnostics.connectionReconnecting(it + 1) }

        val events = diagnostics.connectionDebugReport()
            .substringAfter("recovery_events=")
            .split(",")
        assertEquals(50, events.size)
        assertTrue(events.none { it.contains("gap:111111:24-27") })
        assertTrue(events.none { it.contains("backfill_receive") })
        assertTrue(events.last().contains("reconnect:attempt=46"))
    }

    @Test
    fun rollsCurrentBackfillIntoLastOnReset() {
        val diagnostics = Diagnostics { 0L }
        diagnostics.backfillStarted(ListenCursor(3, 4))
        diagnostics.backfillReceived(10, 500)

        diagnostics.backfillReset()

        val report = diagnostics.connectionDebugReport()
        assertTrue(report.contains("current:none/0/0"))
        assertTrue(report.contains("last:3:4/10/500"))
    }

    private fun acceptedPress() = PttPressResult(
        before = SessionStatus.Ready,
        after = SessionStatus.Requesting,
        actions = listOf(PttAction.StartCapture, PttAction.Request("request-1")),
    )

    private fun ignoredPress(status: SessionStatus = SessionStatus.PlayingEcho) = PttPressResult(
        before = status,
        after = status,
        actions = emptyList(),
    )
}
