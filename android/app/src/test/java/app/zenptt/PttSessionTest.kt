package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttSessionTest {
    @Test
    fun captureStartsPendingAndMatchingGrantEnablesIt() {
        val session = PttSession { "request-1" }
        session.ready()

        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-1")),
            session.press().actions,
        )
        assertFalse(session.granted("other", "other-transmission").recognized)
        assertEquals(
            listOf(PttAction.GrantCapture),
            session.granted("request-1", "transmission-1").actions,
        )
        assertEquals(SessionStatus.Transmitting, session.status)
    }

    @Test
    fun releaseBeforeGrantCancelsAndLateGrantIsImmediatelyFinished() {
        val session = PttSession { "request-1" }
        session.ready()
        session.press()

        assertEquals(
            listOf(PttAction.StopCapture, PttAction.Cancel("request-1")),
            session.release(),
        )
        assertEquals(
            listOf(PttAction.Finish("late-transmission")),
            session.granted("request-1", "late-transmission").actions,
        )
    }

    @Test
    fun releaseAfterGrantStopsCaptureAndSendsImmutableWatermarkControl() {
        val session = PttSession { "request-1" }
        session.ready()
        session.press()
        session.granted("request-1", "transmission-1")

        assertEquals(
            listOf(PttAction.StopCapture, PttAction.Finish("transmission-1")),
            session.release(),
        )
        assertEquals(SessionStatus.Releasing, session.status)
        session.controlSent()
        assertEquals(SessionStatus.Ready, session.status)
    }

    @Test
    fun physicalHoldSurvivesTransportLossAndContinuesOwnedBurstAfterResume() {
        val session = PttSession { "request-1" }
        session.ready()
        session.press()
        session.granted("request-1", "old-transmission")

        assertTrue(session.connectionLost().isEmpty())
        assertTrue(session.physicalHeld())
        assertEquals(SessionStatus.Reconnecting, session.status)
        assertTrue(
            session.resumed(FloorSnapshot("old-transmission", 0, owned = true)).isEmpty(),
        )
        assertEquals(SessionStatus.Transmitting, session.status)
    }

    @Test
    fun oldTerminalAfterResumeDoesNotCancelTheNewRequest() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.press()
        session.granted("request-1", "old-transmission")
        session.connectionLost()

        assertEquals(
            listOf(PttAction.StopCapture, PttAction.Finish("old-transmission")),
            session.resumed(),
        )
        assertEquals(SessionStatus.Releasing, session.status)
        val terminal = requireNotNull(session.ended("old-transmission"))
        assertEquals(LocalTerminalIndicator.ChannelFree, terminal.indicator)
        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-2")),
            terminal.actions,
        )

        assertEquals(
            listOf(PttAction.GrantCapture),
            session.granted("request-2", "new-transmission").actions,
        )
        assertEquals(null, session.ended("old-transmission"))
        assertEquals(SessionStatus.Transmitting, session.status)
        assertEquals(LocalTerminalIndicator.ChannelFree, session.ended("new-transmission")?.indicator)
        assertEquals(SessionStatus.Busy, session.status)
    }

    @Test
    fun heldRecoveryWaitsForRemoteFloorThenCreatesFreshRequest() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.press()
        session.granted("request-1", "old-transmission")
        session.connectionLost()

        assertEquals(
            listOf(PttAction.StopCapture, PttAction.Finish("old-transmission")),
            session.resumed(FloorSnapshot("remote-transmission", 1, owned = false)),
        )
        session.ended("old-transmission")
        assertEquals(SessionStatus.Busy, session.status)

        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-2")),
            session.remoteEnded(),
        )
        assertEquals(SessionStatus.Requesting, session.status)
    }

    @Test
    fun offlinePhysicalReleaseSuppressesAutomaticRequest() {
        val session = PttSession { "request-1" }
        session.ready()
        session.press()
        session.connectionLost()
        session.release()

        assertFalse(session.physicalHeld())
        assertTrue(session.resumed().isEmpty())
        assertEquals(SessionStatus.Ready, session.status)
    }

    @Test
    fun recoveryExpiryStopsOfflineCaptureAndRequiresANewPress() {
        val session = PttSession { "request-1" }
        session.ready()
        session.press()
        session.granted("request-1", "old-transmission")
        session.connectionLost()

        val failure = session.recoveryExpired()

        assertTrue(failure.wasTransmitting)
        assertEquals(
            listOf(
                PttAction.StopCapture,
                PttAction.Finish("old-transmission", interrupted = true),
            ),
            failure.actions,
        )
        assertFalse(session.physicalHeld())
        assertEquals(SessionStatus.Releasing, session.status)
    }

    @Test
    fun lostGrantReusesPendingRequestWhenSnapshotShowsOwnedFloor() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.press()
        session.connectionLost()

        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-1")),
            session.resumed(FloorSnapshot("owned-burst", 0, owned = true)),
        )
        assertEquals(SessionStatus.Requesting, session.status)
        assertEquals(1, request)
    }

    @Test
    fun repeatedConnectionLossKeepsRecoveringRequestCorrelation() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.press()

        assertEquals(listOf(PttAction.StopCapture), session.connectionLost())
        assertTrue(session.connectionLost().isEmpty())
        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-1")),
            session.resumed(FloorSnapshot("owned-burst", 0, owned = true)),
        )
        assertEquals(1, request)
    }

    @Test
    fun connectionLossWhileFinishingWaitsForOldTerminal() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.press()
        session.granted("request-1", "old-transmission")
        session.connectionLost()
        session.resumed()

        assertTrue(session.connectionLost().isEmpty())
        assertTrue(session.resumed().isEmpty())
        assertEquals(SessionStatus.Releasing, session.status)
        assertEquals(1, request)

        val terminal = requireNotNull(session.ended("old-transmission"))
        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-2")),
            terminal.actions,
        )
    }

    @Test
    fun offlineReleaseCancelsPendingRequestOnlyWhenFloorIsOwned() {
        val session = PttSession { "request-1" }
        session.ready()
        session.press()
        session.connectionLost()
        session.release()

        assertEquals(
            listOf(PttAction.Cancel("request-1")),
            session.resumed(FloorSnapshot("owned-burst", 0, owned = true)),
        )
        assertEquals(SessionStatus.Releasing, session.status)
    }

    @Test
    fun lostRequestCreatesFreshIdWhenSnapshotHasNoFloor() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.press()
        session.connectionLost()

        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-2")),
            session.resumed(null),
        )
        assertEquals(2, request)
    }

    @Test
    fun receivingPressIsBusyAndNeverQueuesFloorRequest() {
        val session = PttSession { "request-1" }
        session.ready()
        session.remoteStarted()

        val press = session.press()

        assertEquals(SessionStatus.Receiving, press.before)
        assertEquals(SessionStatus.Busy, press.after)
        assertFalse(press.requestCreated)
        assertTrue(press.actions.isEmpty())
    }

    @Test
    fun busyHoldDoesNotBecomeRequestAfterReconnect() {
        var request = 0
        val session = PttSession { "request-${++request}" }
        session.ready()
        session.remoteStarted()
        session.press()

        assertTrue(session.connectionLost().isEmpty())
        assertTrue(session.resumed().isEmpty())
        assertEquals(SessionStatus.Ready, session.status)
        assertTrue(session.physicalHeld())
        assertEquals(0, request)

        assertTrue(session.release().isEmpty())
        assertEquals(
            listOf(PttAction.StartCapture, PttAction.Request("request-1")),
            session.press().actions,
        )
    }
}
