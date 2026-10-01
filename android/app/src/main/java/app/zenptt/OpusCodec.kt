// Direct libopus binding for fixed-rate encoding and receive-side packet loss concealment.
package app.zenptt

object AudioConstants {
    const val SAMPLE_RATE = 16_000
    const val PLAYBACK_SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 1
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_DURATION_MS / 1000
    const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2
    const val PLAYBACK_SAMPLES_PER_FRAME = PLAYBACK_SAMPLE_RATE * FRAME_DURATION_MS / 1000
    const val PLAYBACK_PCM_BYTES_PER_FRAME = PLAYBACK_SAMPLES_PER_FRAME * 2
}

internal const val OPUS_BITRATE = 16_000
internal const val OPUS_CONSTRAINED_VBR = true

internal object NativeOpus {
    init {
        System.loadLibrary("zenptt_opus")
    }

    external fun encoderCreate(): Long
    external fun encoderConfigure(handle: Long, bitrate: Int, constrainedVbr: Boolean)
    external fun encoderEncode(handle: Long, pcm: ByteArray): ByteArray
    external fun encoderDestroy(handle: Long)
    external fun decoderCreate(): Long
    external fun decoderDecode(handle: Long, packet: ByteArray?): ByteArray
    external fun decoderReset(handle: Long)
    external fun decoderDestroy(handle: Long)
}

class OpusEncoder : AutoCloseable {
    private var handle = NativeOpus.encoderCreate()

    init {
        NativeOpus.encoderConfigure(handle, OPUS_BITRATE, OPUS_CONSTRAINED_VBR)
    }

    fun encode(pcm: ByteArray): List<ByteArray> {
        require(pcm.size == AudioConstants.PCM_BYTES_PER_FRAME)
        check(handle != 0L)
        return listOf(NativeOpus.encoderEncode(handle, pcm))
    }

    override fun close() {
        val closing = handle
        handle = 0
        if (closing != 0L) NativeOpus.encoderDestroy(closing)
    }

}

class OpusDecoder : AutoCloseable {
    private var handle = NativeOpus.decoderCreate()

    fun decode(packet: ByteArray): List<ByteArray> {
        require(packet.isNotEmpty() && packet.size <= AudioFrameCodec.MAX_OPUS_PACKET_BYTES)
        check(handle != 0L)
        return listOf(NativeOpus.decoderDecode(handle, packet))
    }

    fun decodeLoss(frameCount: Int): List<ByteArray> {
        require(frameCount > 0)
        check(handle != 0L)
        return List(frameCount) { NativeOpus.decoderDecode(handle, null) }
    }

    fun reset() {
        check(handle != 0L)
        NativeOpus.decoderReset(handle)
    }

    override fun close() {
        val closing = handle
        handle = 0
        if (closing != 0L) NativeOpus.decoderDestroy(closing)
    }
}
