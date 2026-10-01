package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioSignalOracleTest {
    @Test
    fun realOpusPreservesBothSignalsAndRejectsCorruptions() {
        repeat(2) { variant ->
            TestAudioSignal.assertMatches(
                TestAudioSignal.pcm(48_000, variant),
                TestAudioSignal.decode(TestAudioSignal.encode(TestAudioSignal.pcm(16_000, variant))),
            )
        }
        val input = TestAudioSignal.pcm(16_000)
        val random = Random(12345)
        val noise = ByteBuffer.allocate(input.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(input.size / 2) { putShort(random.nextInt(-8000, 8000).toShort()) }
        }.array()
        val tone = ByteBuffer.allocate(input.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(input.size / 2) { putShort((6000 * sin(2 * PI * 1900 * it / 16_000)).toInt().toShort()) }
        }.array()
        val corruptions = listOf(
            ByteArray(input.size), noise, tone,
            input.copyOfRange(input.size / 2, input.size) + input.copyOfRange(0, input.size / 2),
            input.copyOf(input.size - 6400),
        )
        corruptions.forEach { pcm ->
            val decoded = TestAudioSignal.decode(TestAudioSignal.encode(pcm))
            assertThrows(AssertionError::class.java) {
                TestAudioSignal.assertMatches(TestAudioSignal.pcm(48_000), decoded)
            }
        }
    }
}
