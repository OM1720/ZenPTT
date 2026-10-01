package app.zenptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class OpusCodecTest {
    @Test
    fun encodesAndDecodesDeterministicPcm() {
        val pcm = ByteBuffer.allocate(AudioConstants.PCM_BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(AudioConstants.SAMPLES_PER_FRAME) { index ->
            val sample = (sin(2 * PI * 440 * index / AudioConstants.SAMPLE_RATE) * 10_000).toInt()
            pcm.putShort(sample.toShort())
        }
        val packets = mutableListOf<ByteArray>()
        OpusEncoder().use { encoder ->
            repeat(4) { packets += encoder.encode(pcm.array()) }
        }
        val decoded = mutableListOf<ByteArray>()
        OpusDecoder().use { decoder -> packets.forEach { decoded += decoder.decode(it) } }

        assertTrue(packets.isNotEmpty())
        assertTrue(decoded.any { it.isNotEmpty() })
        assertEquals(
            List(decoded.size) { AudioConstants.PLAYBACK_PCM_BYTES_PER_FRAME },
            decoded.map(ByteArray::size),
        )
    }

    @Test
    fun playbackDecoderResetsOnlyAtTheConsumedBurstBoundary() {
        val firstPackets = encodeTone(440, 2)
        val secondPackets = encodeTone(880, 2)
        val expectedFirst = OpusDecoder().use { decoder -> firstPackets.flatMap(decoder::decode) }
        val expectedSecond = OpusDecoder().use { decoder -> secondPackets.flatMap(decoder::decode) }

        val actual = PlaybackStreamDecoder().use { decoder ->
            listOf(
                decoder.decode("first", firstPackets[0]).single(),
                decoder.decode("first", firstPackets[1]).single(),
                decoder.decode("second", secondPackets[0]).single(),
                decoder.decode("second", secondPackets[1]).single(),
            )
        }

        expectedFirst.zip(actual.take(2)).forEach { (expected, decoded) ->
            assertArrayEquals(expected, decoded)
        }
        expectedSecond.zip(actual.drop(2)).forEach { (expected, decoded) ->
            assertArrayEquals(expected, decoded)
        }
    }

    @Test
    fun lossAtANewBurstStartsWithFreshDecoderState() {
        val packet = encodeTone(440, 1).single()
        val expected = OpusDecoder().use { it.decodeLoss(2) }

        val actual = PlaybackStreamDecoder().use { decoder ->
            decoder.decode("first", packet)
            decoder.decodeLoss("second", 2)
        }

        expected.zip(actual).forEach { (expectedFrame, decoded) ->
            assertArrayEquals(expectedFrame, decoded)
        }
    }

    private fun encodeTone(frequency: Int, frameCount: Int): List<ByteArray> {
        val pcm = ByteBuffer.allocate(AudioConstants.PCM_BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(AudioConstants.SAMPLES_PER_FRAME) { index ->
            val sample = (sin(2 * PI * frequency * index / AudioConstants.SAMPLE_RATE) * 10_000).toInt()
            pcm.putShort(sample.toShort())
        }
        return OpusEncoder().use { encoder ->
            List(frameCount) { encoder.encode(pcm.array()).single() }
        }
    }
}
