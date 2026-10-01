package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMetricsLifecycleTest {
    @Test
    fun metricsAreUnknownUntilPlaybackStarts() {
        val diagnostics = Diagnostics { 1_000L }

        assertEquals(LinkMetrics(), diagnostics.linkMetrics())
        diagnostics.audioFrame(0, 0)

        assertEquals(0L, diagnostics.linkMetrics().recentSequenceGaps)
    }

    @Test
    fun linkResetKeepsCumulativeGapDiagnostics() {
        val diagnostics = Diagnostics { 1_000L }
        diagnostics.audioFrame(0, 10)
        diagnostics.audioFrame(0, 13)
        diagnostics.pongReceived(900)
        diagnostics.pttRequested()
        diagnostics.pttGranted()

        diagnostics.resetLinkMetrics()

        assertEquals(2L, diagnostics.sequenceGaps)
        assertEquals(LinkMetrics(), diagnostics.linkMetrics())
        assertTrueSummaryContainsCumulativeGaps(diagnostics)
    }

    @Test
    fun reconnectResetsPublishedMetricsAndEarlyBurstStartKeepsCurrentPlaybackMetrics() {
        val diagnostics = Diagnostics { 1_000L }
        val viewModel = model(diagnostics)
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        diagnostics.pongReceived(900)
        diagnostics.audioFrame(0, 10)
        diagnostics.audioFrame(0, 12)
        viewModel.onControl(ControlEvent.Pong(1, 900))
        assertNotNull(viewModel.state.value.linkMetrics.rttMs)
        assertEquals(1L, viewModel.state.value.linkMetrics.recentSequenceGaps)

        viewModel.onReconnecting(1)
        assertEquals(LinkMetrics(), viewModel.state.value.linkMetrics)

        viewModel.onControl(
            ControlEvent.BurstStarted(
                "11111111-1111-4111-8111-111111111111",
                0,
            ),
        )
        assertNull(viewModel.state.value.linkMetrics.recentSequenceGaps)
    }

    @Test
    fun frequencyAndServerChangesResetPublishedMetrics() {
        val diagnostics = Diagnostics { 1_000L }
        val viewModel = model(diagnostics)
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()
        diagnostics.pongReceived(900)
        viewModel.onControl(ControlEvent.Pong(1, 900))
        assertNotNull(viewModel.state.value.linkMetrics.rttMs)

        viewModel.applyChannelCode("ROOM2")
        assertEquals(LinkMetrics(), viewModel.state.value.linkMetrics)

        diagnostics.pongReceived(900)
        viewModel.onControl(ControlEvent.Pong(1, 900))
        viewModel.applySettings("wss://other.example", "10")
        assertEquals(LinkMetrics(), viewModel.state.value.linkMetrics)
    }

    @Test
    fun reconnectQualityRecoversOnStablePongs() {
        var now = 1_000L
        val diagnostics = Diagnostics { now }
        val viewModel = ChannelViewModel(
            Store(),
            Connection(),
            diagnostics = diagnostics,
            nowMs = { now },
        )
        viewModel.setChannelCode("ROOM1")
        viewModel.connect()

        viewModel.onReconnecting(1)
        assertEquals(AudioPathQuality.Poor, viewModel.state.value.audioPathQuality)

        viewModel.onControl(ControlEvent.Pong(1, 900))
        assertEquals(AudioPathQuality.Good, viewModel.state.value.audioPathQuality)

        now = 4_000L
        viewModel.onControl(ControlEvent.Pong(2, 3_900))
        assertEquals(AudioPathQuality.Good, viewModel.state.value.audioPathQuality)

        now = 7_000L
        viewModel.onControl(ControlEvent.Pong(3, 6_900))
        assertEquals(AudioPathQuality.Good, viewModel.state.value.audioPathQuality)
    }

    private fun assertTrueSummaryContainsCumulativeGaps(diagnostics: Diagnostics) {
        check(diagnostics.summary().contains("Gaps 2"))
    }

    private fun model(diagnostics: Diagnostics) = ChannelViewModel(
        Store(),
        Connection(),
        diagnostics = diagnostics,
    )

    private class Store : ConnectionPreferences {
        override fun load() = DEFAULT_SERVER_ADDRESS
        override fun save(value: String) = Unit
        override fun loadLastChannel(): String? = null
        override fun saveLastChannel(value: String) = Unit
        override fun loadPowerSaveTimeoutMinutes() = 10
        override fun savePowerSaveTimeoutMinutes(value: Int) = Unit
    }

    private class Connection : ConnectionClient {
        override fun connect(address: String, channel: String, listener: ConnectionListener) = Unit
        override fun requestPtt(requestId: String) = true
        override fun releasePtt(requestId: String) = true
        override fun sendAudio(message: ByteArray) = true
        override fun disconnect() = Unit
    }
}
