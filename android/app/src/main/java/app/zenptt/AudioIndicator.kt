// Generates and plays short local PTT service indicators on the communication route.
package app.zenptt

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class AudioIndicator {
    ChannelFree,
    PttQueued,
    PttRejected,
    TransmissionInterrupted,
}

internal fun audioIndicatorPcm(indicator: AudioIndicator): ShortArray = when (indicator) {
    AudioIndicator.ChannelFree -> dampedTone(680.0, 40, 0.22)
    AudioIndicator.PttQueued -> woodenTap()
    AudioIndicator.PttRejected -> sequence(
        sineTone(360.0, 70, 0.35),
        silence(50),
        sineTone(360.0, 70, 0.35),
    )
    AudioIndicator.TransmissionInterrupted -> sweptTone(700.0, 350.0, 220, 0.45)
}

internal class AudioIndicatorPlayer(
    private val routeController: AudioRouteController,
    private val recordDiagnostic: (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun play(indicator: AudioIndicator) = mutex.withLock {
        check(routeController.prepare("indicator", newPhrase = false)) {
            "Audio indicator route is unavailable"
        }
        val pcm = audioIndicatorPcm(indicator)
        val pcmBytes = pcm.size * Short.SIZE_BYTES
        val minimum = AudioTrack.getMinBufferSize(
            AudioConstants.PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = voiceAudioTrackBuilder()
            .setBufferSizeInBytes(maxOf(minimum, pcmBytes))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Audio indicator track is unavailable min=$minimum buffer=$pcmBytes mode=stream")
        }
        try {
            check(track.setPreferredDevice(routeController.preferredOutput())) { "Indicator output is unavailable" }
            val capacityFrames = track.bufferCapacityInFrames
            check(capacityFrames > 0) { "Audio indicator buffer capacity is unavailable" }
            val requestedThresholdFrames = pcm.size.coerceAtMost(capacityFrames).coerceAtLeast(1)
            val actualThresholdFrames = track.setStartThresholdInFrames(requestedThresholdFrames)
            check(actualThresholdFrames > 0) {
                "Audio indicator start threshold is unavailable: $actualThresholdFrames"
            }
            val primedPcm = if (actualThresholdFrames > pcm.size) {
                pcm.copyOf(actualThresholdFrames)
            } else {
                pcm
            }
            var written = 0
            while (written < primedPcm.size) {
                val count = track.write(
                    primedPcm, written, primedPcm.size - written, AudioTrack.WRITE_BLOCKING,
                )
                check(count > 0) {
                    "Audio indicator write failed: $count at $written/${primedPcm.size}"
                }
                written += count
            }
            recordDiagnostic(
                "indicator prepared type=${indicator.name} capacity=$capacityFrames " +
                    "buffer=${track.bufferSizeInFrames} threshold=$actualThresholdFrames " +
                    "audible=${pcm.size} primed=${primedPcm.size}",
            )
            val startedAt = SystemClock.elapsedRealtime()
            track.play()
            check(routeController.verifyOutput { track.routedDevice }) { "Indicator output did not match" }
            recordDiagnostic(
                "indicator started type=${indicator.name} " +
                    "routed=${routeController.deviceSummary(track.routedDevice)}",
            )
            val drained = withTimeoutOrNull(INDICATOR_DRAIN_TIMEOUT_MS) {
                while (track.playbackHeadPosition < pcm.size) delay(DRAIN_POLL_INTERVAL_MS)
                true
            } ?: false
            val playbackHead = track.playbackHeadPosition
            recordDiagnostic(
                "indicator drain type=${indicator.name} written=$written head=$playbackHead " +
                    "underruns=${track.underrunCount} completed=$drained " +
                    "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            check(drained) {
                "Audio indicator drain timed out: $playbackHead/${pcm.size}"
            }
        } catch (error: CancellationException) {
            throw error
        } finally {
            runCatching { track.stop() }
            track.release()
            recordDiagnostic("indicator ended type=${indicator.name}")
        }
    }
}

private fun woodenTap(): ShortArray {
    val size = samples(WOOD_TAP_DURATION_MS)
    val attackSamples = samples(WOOD_TAP_ATTACK_MS).coerceAtLeast(1)
    val transientSamples = samples(WOOD_TAP_TRANSIENT_MS).coerceAtLeast(1)
    var lowPhase = 0.0
    var highPhase = 0.0
    var noiseState = 0x13579BDF
    return ShortArray(size) { index ->
        val progress = if (size <= 1) 1.0 else index.toDouble() / (size - 1)
        val attack = (index.toDouble() / attackSamples).coerceIn(0.0, 1.0)
        val decay = exp(-WOOD_TAP_DECAY * progress)
        val resonances = 0.7 * sin(lowPhase) + 0.3 * sin(highPhase)
        lowPhase += 2.0 * PI * WOOD_TAP_LOW_FREQUENCY_HZ / AudioConstants.PLAYBACK_SAMPLE_RATE
        highPhase += 2.0 * PI * WOOD_TAP_HIGH_FREQUENCY_HZ / AudioConstants.PLAYBACK_SAMPLE_RATE
        noiseState = noiseState * 1103515245 + 12345
        val noise = (((noiseState ushr 16) and 0x7fff) / 16383.5 - 1.0)
        val transient = if (index < transientSamples) {
            WOOD_TAP_NOISE_WEIGHT * noise * (1.0 - index.toDouble() / transientSamples)
        } else {
            0.0
        }
        val endpoint = if (index == size - 1) 0.0 else 1.0
        val normalized = (resonances + transient) / (1.0 + WOOD_TAP_NOISE_WEIGHT)
        (normalized * attack * decay * endpoint * WOOD_TAP_AMPLITUDE * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun dampedTone(frequencyHz: Double, durationMs: Int, amplitude: Double): ShortArray {
    val size = samples(durationMs)
    val attackSamples = samples(DRY_ATTACK_MS).coerceAtMost(size / 2)
    var phase = 0.0
    return ShortArray(size) { index ->
        val attack = if (attackSamples == 0 || index >= attackSamples) {
            1.0
        } else {
            index.toDouble() / attackSamples
        }
        val decayProgress = if (size <= 1) 1.0 else index.toDouble() / (size - 1)
        val envelope = attack * (1.0 - decayProgress).pow(2)
        val sample = sin(phase)
        phase += 2.0 * PI * frequencyHz / AudioConstants.PLAYBACK_SAMPLE_RATE
        (sample * envelope * amplitude * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun sineTone(frequencyHz: Double, durationMs: Int, amplitude: Double): ShortArray {
    var phase = 0.0
    return tone(durationMs, amplitude) { _, _ ->
        val sample = sin(phase)
        phase += 2.0 * PI * frequencyHz / AudioConstants.PLAYBACK_SAMPLE_RATE
        sample
    }
}

private fun sweptTone(
    startFrequencyHz: Double,
    endFrequencyHz: Double,
    durationMs: Int,
    amplitude: Double,
): ShortArray {
    var phase = 0.0
    return tone(durationMs, amplitude) { index, size ->
        val progress = if (size <= 1) 1.0 else index.toDouble() / (size - 1)
        val frequency = startFrequencyHz + (endFrequencyHz - startFrequencyHz) * progress
        val sample = sin(phase)
        phase += 2.0 * PI * frequency / AudioConstants.PLAYBACK_SAMPLE_RATE
        sample
    }
}

private fun tone(
    durationMs: Int,
    amplitude: Double,
    sample: (index: Int, size: Int) -> Double,
): ShortArray {
    val size = samples(durationMs)
    val fadeSamples = samples(FADE_MS).coerceAtMost(size / 2)
    return ShortArray(size) { index ->
        val envelope = when {
            fadeSamples == 0 -> 1.0
            index < fadeSamples -> index.toDouble() / fadeSamples
            index >= size - fadeSamples -> (size - 1 - index).toDouble() / fadeSamples
            else -> 1.0
        }.coerceIn(0.0, 1.0)
        (sample(index, size) * envelope * amplitude * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun silence(durationMs: Int) = ShortArray(samples(durationMs))

private fun sequence(vararg parts: ShortArray): ShortArray {
    val result = ShortArray(parts.sumOf(ShortArray::size))
    var offset = 0
    parts.forEach { part ->
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

private fun samples(durationMs: Int): Int =
    AudioConstants.PLAYBACK_SAMPLE_RATE * durationMs / 1_000

private const val FADE_MS = 5
private const val DRAIN_POLL_INTERVAL_MS = 5L
private const val DRY_ATTACK_MS = 1
private const val INDICATOR_DRAIN_TIMEOUT_MS = 1_000L
private const val WOOD_TAP_DURATION_MS = 32
private const val WOOD_TAP_ATTACK_MS = 1
private const val WOOD_TAP_TRANSIENT_MS = 2
private const val WOOD_TAP_LOW_FREQUENCY_HZ = 1_200.0
private const val WOOD_TAP_HIGH_FREQUENCY_HZ = 1_900.0
private const val WOOD_TAP_NOISE_WEIGHT = 0.12
private const val WOOD_TAP_DECAY = 7.0
private const val WOOD_TAP_AMPLITUDE = 0.14
