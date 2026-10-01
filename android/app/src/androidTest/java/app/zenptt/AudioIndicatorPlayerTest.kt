package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioIndicatorPlayerTest {
    @Test
    fun realAudioTrackPlaysReleasesAndRecoversAfterCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val events = CopyOnWriteArrayList<String>()
        val routeController = AudioRouteController(context, events::add)
        val player = AudioIndicatorPlayer(routeController, events::add)

        try {
            repeat(2) {
                AudioIndicator.entries.forEach { indicator -> player.play(indicator) }
            }
            repeat(20) { player.play(AudioIndicator.ChannelFree) }

            val interrupted = launch {
                player.play(AudioIndicator.TransmissionInterrupted)
            }
            withTimeout(5_000) {
                while (
                    events.count {
                        it.startsWith("indicator started type=TransmissionInterrupted")
                    } < 3
                ) {
                    delay(5)
                }
            }
            interrupted.cancelAndJoin()

            player.play(AudioIndicator.ChannelFree)

            val starts = events.filter { it.startsWith("indicator started type=") }
            val ends = events.filter { it.startsWith("indicator ended type=") }
            val routeRequests = events.filter { it.startsWith("indicator route requested=") }
            val prepared = events.filter { it.startsWith("indicator prepared type=") }
            val drains = events.filter { it.startsWith("indicator drain type=") }
            val preparedPattern = Regex(
                """capacity=(\d+) buffer=(\d+) threshold=(\d+) audible=(\d+) primed=(\d+)""",
            )
            val drainPattern = Regex(
                """written=(\d+) head=(\d+) underruns=(\d+) completed=true""",
            )
            val expectedStarts = AudioIndicator.entries.size * 2 + 22
            assertEquals(expectedStarts, starts.size)
            assertEquals(expectedStarts, ends.size)
            assertEquals(expectedStarts, routeRequests.size)
            assertEquals(expectedStarts, prepared.size)
            assertEquals(expectedStarts - 1, drains.size)
            prepared.forEach { event ->
                val values = requireNotNull(preparedPattern.find(event))
                    .groupValues.drop(1).map(String::toInt)
                val (capacity, buffer, threshold, audible, primed) = values
                assertTrue(buffer <= capacity)
                assertTrue(threshold in 1..capacity)
                assertTrue(primed >= threshold)
                assertTrue(primed >= audible)
            }
            drains.forEach { event ->
                val values = requireNotNull(drainPattern.find(event))
                    .groupValues.drop(1).map(String::toInt)
                val (written, head) = values
                assertTrue(head > 0)
                assertTrue(written >= head)
            }
            assertFalse(events.any { it.contains("indicator failure") })
        } finally {
            routeController.clear(
                "indicator_test",
                AudioRouteStatus.Inactive,
                AudioRouteStatus.Inactive,
            )
            routeController.unregister()
        }
    }
}
