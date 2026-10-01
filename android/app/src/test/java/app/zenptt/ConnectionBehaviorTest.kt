package app.zenptt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBehaviorTest {
    @Test
    fun savedFrequencyAutoConnectsUnlessExplicitlyDisconnected() {
        val offline = ChannelUiState(channelCode = "ROOM1", currentChannel = null)

        assertTrue(shouldAutoConnect(offline, explicitlyDisconnected = false))
        assertFalse(shouldAutoConnect(offline, explicitlyDisconnected = true))
        assertFalse(shouldAutoConnect(ChannelUiState(channelCode = ""), explicitlyDisconnected = false))
        assertFalse(
            shouldAutoConnect(
                offline.copy(currentChannel = "ROOM1"),
                explicitlyDisconnected = false,
            ),
        )
    }

    @Test
    fun onlyNewFrequencyConnectsAnOfflineSession() {
        val offline = ChannelUiState(channelCode = "ROOM1", currentChannel = null)

        assertFalse(shouldConnectAfterFrequencySubmit(offline, "ROOM1"))
        assertTrue(shouldConnectAfterFrequencySubmit(offline, "ROOM2"))
        assertFalse(shouldConnectAfterFrequencySubmit(offline, null))
        assertFalse(
            shouldConnectAfterFrequencySubmit(
                offline.copy(currentChannel = "ROOM1"),
                "ROOM2",
            ),
        )
    }
}