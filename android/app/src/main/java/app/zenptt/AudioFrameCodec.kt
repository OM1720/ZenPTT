// Encodes and validates the aggregated ZenPTT v4 media envelope.
package app.zenptt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class MediaDirection(val wireValue: Byte) { Uplink(1), Downlink(2) }

data class NetworkAudioEnvelope(
    val direction: MediaDirection,
    val burstId: String,
    val firstSequence: Long,
    val opusPackets: List<ByteArray>,
)

object AudioFrameCodec {
    const val HEADER_SIZE = 24
    const val MAX_MESSAGE_BYTES = 4096
    const val MAX_OPUS_PACKET_BYTES = 1275
    const val MAX_FRAMES_PER_MESSAGE = 50
    private const val VERSION: Byte = 4

    internal fun canAppendPacket(currentMessageBytes: Int, packetCount: Int, packet: ByteArray): Boolean {
        require(packet.isNotEmpty() && packet.size <= MAX_OPUS_PACKET_BYTES)
        return packetCount < MAX_FRAMES_PER_MESSAGE &&
            currentMessageBytes + Short.SIZE_BYTES + packet.size <= MAX_MESSAGE_BYTES
    }

    fun encode(envelope: NetworkAudioEnvelope): ByteArray {
        require(envelope.firstSequence in 0..0xffffffffL)
        require(envelope.opusPackets.size in 1..MAX_FRAMES_PER_MESSAGE)
        require(envelope.firstSequence + envelope.opusPackets.lastIndex <= 0xffffffffL)
        val burst = UUID.fromString(envelope.burstId)
        val messageSize = HEADER_SIZE + envelope.opusPackets.sumOf { packet ->
            require(packet.isNotEmpty() && packet.size <= MAX_OPUS_PACKET_BYTES)
            Short.SIZE_BYTES + packet.size
        }
        require(messageSize <= MAX_MESSAGE_BYTES)
        return ByteBuffer.allocate(messageSize).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION).put(envelope.direction.wireValue)
            .putLong(burst.mostSignificantBits).putLong(burst.leastSignificantBits)
            .putInt(envelope.firstSequence.toInt()).putShort(envelope.opusPackets.size.toShort())
            .apply {
                envelope.opusPackets.forEach { packet -> putShort(packet.size.toShort()).put(packet) }
            }.array()
    }

    fun decode(message: ByteArray, expectedDirection: MediaDirection? = null): NetworkAudioEnvelope {
        require(message.size in (HEADER_SIZE + Short.SIZE_BYTES + 1)..MAX_MESSAGE_BYTES)
        val buffer = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN)
        require(buffer.get() == VERSION)
        val direction = MediaDirection.entries.firstOrNull { it.wireValue == buffer.get(buffer.position()) }
            ?: throw IllegalArgumentException("Invalid media direction")
        buffer.get()
        require(expectedDirection == null || direction == expectedDirection)
        val burstId = UUID(buffer.long, buffer.long).toString()
        val firstSequence = buffer.int.toLong() and 0xffffffffL
        val frameCount = buffer.short.toInt() and 0xffff
        require(frameCount in 1..MAX_FRAMES_PER_MESSAGE)
        require(firstSequence + frameCount - 1 <= 0xffffffffL)
        val packets = ArrayList<ByteArray>(frameCount)
        repeat(frameCount) {
            require(buffer.remaining() >= Short.SIZE_BYTES)
            val size = buffer.short.toInt() and 0xffff
            require(size in 1..MAX_OPUS_PACKET_BYTES && buffer.remaining() >= size)
            packets += ByteArray(size).also(buffer::get)
        }
        require(!buffer.hasRemaining())
        return NetworkAudioEnvelope(direction, burstId, firstSequence, packets)
    }
}
