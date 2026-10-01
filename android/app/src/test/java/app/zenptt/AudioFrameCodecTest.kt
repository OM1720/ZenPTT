package app.zenptt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameCodecTest {
    @Test
    fun fixedWireVectorsAreIndependentOfTheEncoder() {
        val vectors = listOf(
            Triple("04020123456789abcdef0123456789abcdef01020304000200030180ff0002007f",
                0x01020304L, listOf(byteArrayOf(1, 0x80.toByte(), 0xff.toByte()), byteArrayOf(0, 0x7f))),
            Triple("04020123456789abcdef0123456789abcdefffffffff00010001ff",
                0xffffffffL, listOf(byteArrayOf(0xff.toByte()))),
        )
        for ((hex, sequence, packets) in vectors) {
            val wire = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val expected = NetworkAudioEnvelope(
                MediaDirection.Downlink, "01234567-89ab-cdef-0123-456789abcdef", sequence, packets,
            )
            assertArrayEquals(wire, AudioFrameCodec.encode(expected))
            val decoded = AudioFrameCodec.decode(wire, MediaDirection.Downlink)
            assertEquals(expected.burstId, decoded.burstId)
            assertEquals(sequence, decoded.firstSequence)
            assertEquals(packets.size, decoded.opusPackets.size)
            packets.indices.forEach { assertArrayEquals(packets[it], decoded.opusPackets[it]) }
            for (length in wire.indices) {
                assertThrows(IllegalArgumentException::class.java) {
                    AudioFrameCodec.decode(wire.copyOf(length), MediaDirection.Downlink)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                AudioFrameCodec.decode(wire + byteArrayOf(0), MediaDirection.Downlink)
            }
        }
    }

    @Test
    fun v4UsesFixedSixteenKilobitConstrainedVbr() {
        assertEquals(16_000, OPUS_BITRATE)
        assertTrue(OPUS_CONSTRAINED_VBR)
    }

    @Test
    fun v4EnvelopeRoundTrips() {
        val source = NetworkAudioEnvelope(MediaDirection.Downlink, BURST, 7, listOf(byteArrayOf(1), byteArrayOf(2, 3)))
        val encoded = AudioFrameCodec.encode(source)
        val decoded = AudioFrameCodec.decode(encoded, MediaDirection.Downlink)
        assertEquals(4, encoded[0].toInt())
        assertEquals(AudioFrameCodec.HEADER_SIZE, 24)
        assertEquals(source.burstId, decoded.burstId)
        assertEquals(7, decoded.firstSequence)
        assertArrayEquals(source.opusPackets[1], decoded.opusPackets[1])
    }

    @Test
    fun rejectsV2AndWrongDirection() {
        val encoded = AudioFrameCodec.encode(NetworkAudioEnvelope(MediaDirection.Uplink, BURST, 0, listOf(byteArrayOf(1))))
        assertThrows(IllegalArgumentException::class.java) {
            AudioFrameCodec.decode(encoded, MediaDirection.Downlink)
        }
        encoded[0] = 2
        assertThrows(IllegalArgumentException::class.java) { AudioFrameCodec.decode(encoded) }
    }

    @Test
    fun rejectsTheFirstByteAndFrameCountBeyondWireLimits() {
        val oneByteTooLarge = listOf(1_275, 1_275, 1_275, 240).map { ByteArray(it) { 1 } }
        assertThrows(IllegalArgumentException::class.java) {
            AudioFrameCodec.encode(
                NetworkAudioEnvelope(MediaDirection.Uplink, BURST, 0, oneByteTooLarge),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioFrameCodec.encode(
                NetworkAudioEnvelope(
                    MediaDirection.Uplink,
                    BURST,
                    0,
                    List(AudioFrameCodec.MAX_FRAMES_PER_MESSAGE + 1) { byteArrayOf(1) },
                ),
            )
        }
    }

    private companion object { const val BURST = "11111111-1111-4111-8111-111111111111" }
}
