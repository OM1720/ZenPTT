package app.zenptt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal object TestAudioSignal {
    const val FRAMES = 100

    fun pcm(rate: Int, variant: Int = 0): ByteArray {
        val levels = doubleArrayOf(0.2, 0.7, 0.35, 0.9, 0.15, 0.55, 0.8, 0.3)
        return ByteBuffer.allocate(rate * 2 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(rate * 2) { i ->
                val block = i / (rate / 4)
                val frequency = if ((block + variant) % 2 == 0) 700 else 1100
                putShort((12_000 * levels[block % levels.size] * sin(2 * PI * frequency * i / rate)).toInt().toShort())
            }
        }.array()
    }

    fun encode(pcm: ByteArray): List<ByteArray> = OpusEncoder().use { encoder ->
        pcm.asList().chunked(AudioConstants.PCM_BYTES_PER_FRAME).flatMap { encoder.encode(it.toByteArray()) }
    }

    fun decode(packets: List<ByteArray>): ByteArray = OpusDecoder().use { decoder ->
        packets.flatMap { decoder.decode(it) }.fold(byteArrayOf()) { all, pcm -> all + pcm }
    }

    private fun samples(pcm: ByteArray): DoubleArray {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(pcm.size / 2) { buffer.short.toDouble() }
    }

    private fun envelope(pcm: DoubleArray): List<Double> = pcm.asList().chunked(960)
        .map { frame -> frame.sumOf(::abs) / frame.size }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        val ma = a.average()
        val mb = b.average()
        val aa = a.sumOf { (it - ma) * (it - ma) }
        val bb = b.sumOf { (it - mb) * (it - mb) }
        return if (aa * bb == 0.0) 0.0 else a.indices.sumOf { (a[it] - ma) * (b[it] - mb) } / sqrt(aa * bb)
    }

    private fun spectrum(pcm: DoubleArray): List<Double> = (1..64).map { band ->
        val coefficient = 2 * cos(2 * PI * band / 128)
        var power = 0.0
        for (start in 0 until pcm.size - 768 step 960) {
            var previous = 0.0
            var older = 0.0
            repeat(128) { i ->
                val current = pcm[start + i * 6] + coefficient * previous - older
                older = previous
                previous = current
            }
            power += max(0.0, previous * previous + older * older - coefficient * previous * older)
        }
        power
    }

    fun assertMatches(reference: ByteArray, received: ByteArray) {
        assertEquals("PCM duration", reference.size, received.size)
        val source = samples(reference)
        val result = samples(received)
        val a = envelope(source)
        val b = envelope(result)
        val best = (-5..5).maxOf { lag ->
            val left = max(0, -lag)
            val right = max(0, lag)
            val size = min(a.size - left, b.size - right)
            correlation(a.subList(left, left + size), b.subList(right, right + size))
        }
        assertTrue("PCM envelope correlation=$best", best > 0.9)
        val x = spectrum(source)
        val y = spectrum(result)
        val scale = sqrt(x.sumOf { it * it } * y.sumOf { it * it })
        val similarity = if (scale == 0.0) 0.0 else x.indices.sumOf { x[it] * y[it] } / scale
        assertTrue("PCM spectrum similarity=$similarity", similarity > 0.9)
    }
}

// Only capture is synthetic; callers may delegate receive/playback to the real pipeline.
internal class SignalAudioGate(private val playback: AudioGate = NoOpAudioGate) : AudioGate by playback {
    val sent = AtomicInteger()
    val received = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    var packets: List<ByteArray> = emptyList()
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var producer: Job? = null
    private var sender: ((ByteArray) -> Boolean)? = null
    @Volatile var failure: String? = null

    fun prepare(variant: Int) {
        check(producer?.isActive != true)
        sent.set(0)
        failure = null
        packets = TestAudioSignal.encode(TestAudioSignal.pcm(16_000, variant))
    }

    override fun start(sender: (ByteArray) -> Boolean) { this.sender = sender }
    override fun start(sender: (ByteArray) -> Boolean, onFailure: (AudioCaptureFailure) -> Unit) = start(sender)
    override fun grantCapture() {
        val send = requireNotNull(sender)
        producer = scope.launch {
            for (packet in packets) {
                if (!send(packet)) { failure = "Granted source packet was rejected"; break }
                sent.incrementAndGet()
                delay(20)
            }
        }
    }

    override fun stopCapture(onStopped: () -> Unit) {
        sender = null
        val job = producer
        job?.cancel()
        scope.launch { job?.join(); onStopped() }
    }
    override fun stop(onStopped: () -> Unit) = stopCapture { playback.stop(onStopped) }
    override fun play(burstId: String, message: ByteArray): Boolean {
        val accepted = playback.play(burstId, message)
        if (accepted) received += burstId to message.copyOf()
        return accepted
    }
    override fun close() {
        runBlocking { withTimeout(5_000) { producer?.cancel(); producer?.join() } }
        scope.cancel()
    }
}

internal class RecordedConnection(private val transport: ConnectionClient) : ConnectionClient by transport {
    val controls = CopyOnWriteArrayList<ControlEvent>()
    override fun connect(address: String, channel: String, listener: ConnectionListener) {
        transport.connect(address, channel, object : ConnectionListener by listener {
            override fun onControl(event: ControlEvent) {
                controls += event
                listener.onControl(event)
            }
        })
    }
}
