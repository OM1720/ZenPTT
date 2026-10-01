package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessiblePttTest {
    @Test
    fun accessibilityActionStartsStopsAndRejectsUnavailableStates() {
        assertEquals(AccessiblePttAction.Start, accessiblePttAction(ChannelUiState()))
        assertEquals(AccessiblePttAction.Start, action(SessionStatus.Ready))
        assertEquals(AccessiblePttAction.Start, action(SessionStatus.Receiving))
        assertEquals(AccessiblePttAction.Stop, action(SessionStatus.Requesting))
        assertEquals(AccessiblePttAction.Stop, action(SessionStatus.Transmitting))
        assertEquals(AccessiblePttAction.Stop, action(SessionStatus.Busy))
        assertEquals(AccessiblePttAction.Start, action(SessionStatus.Connecting))
        assertEquals(AccessiblePttAction.Start, action(SessionStatus.Reconnecting))
        assertEquals(AccessiblePttAction.Start, action(SessionStatus.ConnectionError))
        assertEquals(AccessiblePttAction.Disabled, action(SessionStatus.Releasing))
        assertEquals(AccessiblePttAction.Disabled, action(SessionStatus.PlayingEcho))
        assertEquals(
            AccessiblePttAction.Start,
            accessiblePttAction(
                state(SessionStatus.PlayingEcho).copy(playbackInterruptible = true),
            ),
        )
        assertEquals(
            AccessiblePttAction.Stop,
            accessiblePttAction(state(SessionStatus.Connecting).copy(pendingPtt = true)),
        )
    }

    @Test
    fun lifecycleCleanupPolicyCoversLossBackgroundStates() {
        assertTrue(shouldReleaseAccessiblePtt(ChannelUiState()))
        assertTrue(shouldReleaseAccessiblePtt(state(SessionStatus.Connecting)))
        assertTrue(shouldReleaseAccessiblePtt(state(SessionStatus.Reconnecting)))
        assertTrue(shouldReleaseAccessiblePtt(state(SessionStatus.ConnectionError)))
        assertTrue(shouldReleaseAccessiblePtt(state(SessionStatus.Releasing)))
        assertTrue(shouldReleaseAccessiblePtt(state(SessionStatus.PlayingEcho)))
        assertFalse(
            shouldReleaseAccessiblePtt(state(SessionStatus.Connecting).copy(pendingPtt = true)),
        )
        assertFalse(shouldReleaseAccessiblePtt(state(SessionStatus.Ready)))
        assertFalse(shouldReleaseAccessiblePtt(state(SessionStatus.Transmitting)))
    }

    @Test
    fun releasingAccessibilityDoesNotReleaseBm008() {
        var downs = 0
        var ups = 0
        val latch = PttInputLatch({ downs++ }, { ups++ })

        latch.down(PttInputSource.Accessibility)
        latch.down(PttInputSource.Hardware)
        latch.up(PttInputSource.Accessibility)

        assertEquals(1, downs)
        assertEquals(0, ups)

        latch.up(PttInputSource.Hardware)
        assertEquals(1, ups)
    }

    @Test
    fun eachInputSourceIsIdempotent() {
        var downs = 0
        var ups = 0
        val latch = PttInputLatch({ downs++ }, { ups++ })

        latch.down(PttInputSource.Accessibility)
        latch.down(PttInputSource.Accessibility)
        latch.up(PttInputSource.Accessibility)
        latch.up(PttInputSource.Accessibility)

        assertEquals(1, downs)
        assertEquals(1, ups)
    }

    private fun action(status: SessionStatus) = accessiblePttAction(state(status))

    private fun state(status: SessionStatus) = ChannelUiState(
        currentChannel = "ROOM1",
        status = status,
    )
}
