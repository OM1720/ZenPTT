package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.TimeoutCancellationException

@RunWith(AndroidJUnit4::class)
class EchoEndToEndTest {
    @Test
    fun deterministicAudioCompletesFullEchoRoundTrip() = runBlocking {
        val audio = SignalAudioGate()
        val connection = RecordedConnection(ZenWebSocketClient())
        val viewModel = ChannelViewModel(
            MemoryAddressStore(),
            connection,
            audio,
            healthClient = OkHttpServerHealthClient(),
        )
        val responseIds = mutableSetOf<String>()
        val sourceIds = mutableSetOf<String>()
        var stage = "connect"
        try {
            viewModel.setServerAddress(testServerAddress())
            stage = "health"
            viewModel.pingServer()
            withTimeout(10_000) {
                viewModel.state.first { it.serverCheckStatus == "Server available" }
            }
            stage = "connect"
            viewModel.connect(echo = true)
            awaitStatus(viewModel, SessionStatus.Ready)

            repeat(2) {
                val variant = it
                audio.prepare(variant)
                val controlStart = connection.controls.size
                val receivedStart = audio.received.size
                stage = "round ${it + 1} grant"
                viewModel.pttDown()
                awaitStatus(viewModel, SessionStatus.Transmitting)
                stage = "round ${it + 1} send"
                withTimeout(5_000) {
                    while (audio.sent.get() < TestAudioSignal.FRAMES && audio.failure == null) delay(10)
                }
                assertEquals(null, audio.failure)
                assertEquals(TestAudioSignal.FRAMES, audio.sent.get())
                viewModel.pttUp()
                stage = "round ${it + 1} playback"
                withTimeout(10_000) {
                    while (audio.received.size - receivedStart < TestAudioSignal.FRAMES ||
                        viewModel.state.value.status != SessionStatus.Ready) {
                        delay(10)
                    }
                }
                val controls = connection.controls.drop(controlStart)
                val grant = controls.filterIsInstance<ControlEvent.PttGranted>().single()
                assertTrue(sourceIds.add(grant.burstId))
                val response = audio.received.drop(receivedStart)
                assertEquals(TestAudioSignal.FRAMES, response.size)
                val responseId = response.map { frame -> frame.first }.distinct().single()
                assertTrue(responseIds.add(responseId))
                assertTrue(responseId != grant.burstId)
                val sealed = controls.filterIsInstance<ControlEvent.BurstSealed>().single { event -> event.burstId == responseId }
                assertEquals(TestAudioSignal.FRAMES.toLong(), sealed.finalNextSequence)
                assertEquals("complete", sealed.reason)
                val terminal = controls.filterIsInstance<ControlEvent.PttEnded>().last { event -> event.burstId == grant.burstId }
                assertEquals(TestAudioSignal.FRAMES.toLong(), terminal.finalNextSequence)
                assertEquals("sealed", terminal.state)
                TestAudioSignal.assertMatches(
                    TestAudioSignal.pcm(48_000, variant),
                    TestAudioSignal.decode(response.map { frame -> frame.second }),
                )
            }

            stage = "diagnostics"
            withTimeout(12_000) {
                viewModel.state.first {
                    it.diagnostics.contains("RTT ") && !it.diagnostics.contains("RTT —")
                }
            }
            assertTrue(viewModel.state.value.diagnostics.contains("Gaps 0"))
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(
                "Timeout at $stage; state=${viewModel.state.value}; " +
                    "sent=${audio.sent.get()} received=${audio.received.size}",
                error,
            )
        } finally {
            viewModel.disconnect()
            audio.close()
            viewModel.close()
        }
    }

    private suspend fun awaitStatus(viewModel: ChannelViewModel, status: SessionStatus) {
        withTimeout(10_000) { viewModel.state.first { it.status == status } }
    }

    private class MemoryAddressStore : ConnectionPreferences {
        private var value = ""
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

}
