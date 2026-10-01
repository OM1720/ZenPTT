package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloVisualSpecTest {
    @Test
    fun connectionIconReflectsConnectionState() {
        assertEquals(ConnectionIconMode.Off, connectionIconMode(ChannelUiState()))
        assertEquals(ConnectionIconMode.Connected, connectionIconMode(connected()))
        assertEquals(
            ConnectionIconMode.Connecting,
            connectionIconMode(connected().copy(status = SessionStatus.Connecting)),
        )
        assertEquals(
            ConnectionIconMode.Connecting,
            connectionIconMode(connected().copy(status = SessionStatus.Reconnecting)),
        )
        assertEquals(
            ConnectionIconMode.Off,
            connectionIconMode(connected().copy(status = SessionStatus.ConnectionError)),
        )
        assertEquals(600, CONNECTION_ICON_BLINK_MS)
    }

    @Test
    fun qualitySelectsReviewedGapFractions() {
        assertEquals(0f, spec(SessionStatus.Ready, AudioPathQuality.Good).gapFraction)
        assertEquals(HALO_FAIR_GAP_FRACTION, spec(SessionStatus.Ready, AudioPathQuality.Fair).gapFraction)
        assertEquals(HALO_POOR_GAP_FRACTION, spec(SessionStatus.Ready, AudioPathQuality.Poor).gapFraction)
        assertEquals(HALO_SEGMENT_COUNT, 16)
    }

    @Test
    fun goodTransitionAddsVisibleGapsAndRotation() {
        val transition = spec(SessionStatus.Connecting, AudioPathQuality.Good)

        assertEquals(HaloMode.Transition, transition.mode)
        assertEquals(HALO_TRANSITION_GOOD_GAP_FRACTION, transition.gapFraction)
        assertTrue(transition.ringVisible)
        assertTrue(transition.rotating)
    }

    @Test
    fun reconnectKeepsVolumeIconWhilePlaybackIsActive() {
        val reconnecting = haloVisualSpec(
            connected().copy(
                status = SessionStatus.Reconnecting,
                audioPathQuality = AudioPathQuality.Poor,
                playbackActive = true,
            ),
        )

        assertEquals(HaloMode.Transition, reconnecting.mode)
        assertEquals(HaloCenterIcon.Volume, reconnecting.centerIcon)
        assertTrue(reconnecting.rotating)
        assertEquals(HALO_POOR_GAP_FRACTION, reconnecting.gapFraction)
        assertEquals(HaloCenterIcon.Microphone, spec(SessionStatus.Reconnecting).centerIcon)
    }

    @Test
    fun stableStatesKeepQualityRingStatic() {
        listOf(SessionStatus.Ready, SessionStatus.Transmitting, SessionStatus.Receiving).forEach { status ->
            val visual = spec(status, AudioPathQuality.Fair)
            assertTrue(visual.ringVisible)
            assertFalse(visual.rotating)
            assertEquals(HALO_FAIR_GAP_FRACTION, visual.gapFraction)
        }
    }

    @Test
    fun centerVisualsDistinguishAudioAndFailureStates() {
        val offline = haloVisualSpec(ChannelUiState())
        assertEquals(HaloMode.Offline, offline.mode)
        assertEquals(HaloCenterIcon.Microphone, offline.centerIcon)
        assertFalse(offline.ringVisible)

        val connectionError = haloVisualSpec(ChannelUiState(status = SessionStatus.ConnectionError))
        assertEquals(HaloMode.ConnectionError, connectionError.mode)
        assertEquals(HaloCenterIcon.ConnectionAlert, connectionError.centerIcon)
        assertFalse(connectionError.ringVisible)

        val transmitting = spec(SessionStatus.Transmitting)
        assertTrue(transmitting.fillCenter)
        assertEquals(HaloCenterIcon.Microphone, transmitting.centerIcon)

        assertEquals(HaloCenterIcon.Volume, spec(SessionStatus.Receiving).centerIcon)
        assertEquals(HaloCenterIcon.Volume, spec(SessionStatus.PlayingEcho).centerIcon)
        assertEquals(HaloCenterIcon.MicrophoneOff, spec(SessionStatus.Busy).centerIcon)
        assertEquals(
            HaloCenterIcon.MicrophoneOff,
            haloVisualSpec(connected().copy(pttError = "PTT failed")).centerIcon,
        )
    }

    @Test
    fun receiveQualityUsesLossAndPlaybackState() {
        assertEquals(AudioPathQuality.Good, receiveAudioPathQuality(0, false))
        assertEquals(AudioPathQuality.Fair, receiveAudioPathQuality(3, false))
        assertEquals(AudioPathQuality.Poor, receiveAudioPathQuality(4, false))
        assertEquals(AudioPathQuality.Poor, receiveAudioPathQuality(0, true))
    }

    private fun connected() = ChannelUiState(
        currentChannel = "ROOM1",
        status = SessionStatus.Ready,
    )

    private fun spec(
        status: SessionStatus,
        quality: AudioPathQuality = AudioPathQuality.Good,
    ) = haloVisualSpec(
        connected().copy(status = status, audioPathQuality = quality),
    )
}
