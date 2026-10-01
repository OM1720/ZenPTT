package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AudioIndicatorTest {
    @Test
    fun generatedIndicatorsHaveExactDurationsAndNoClipping() {
        val channelFree = audioIndicatorPcm(AudioIndicator.ChannelFree)
        val queued = audioIndicatorPcm(AudioIndicator.PttQueued)
        val rejected = audioIndicatorPcm(AudioIndicator.PttRejected)
        val interrupted = audioIndicatorPcm(AudioIndicator.TransmissionInterrupted)

        assertEquals(samples(40), channelFree.size)
        assertEquals(samples(32), queued.size)
        assertEquals(samples(190), rejected.size)
        assertEquals(samples(220), interrupted.size)
        assertPeakAtMost(channelFree, 0.22)
        assertPeakAtMost(queued, 0.14)
        assertPeakAtMost(rejected, 0.35)
        assertPeakAtMost(interrupted, 0.45)
    }

    @Test
    fun channelFreeIndicatorIsAHighShortDampedTone() {
        val pcm = audioIndicatorPcm(AudioIndicator.ChannelFree)
        val quarter = pcm.size / 4

        val frequencyHz = estimatedFrequencyHz(pcm)
        val earlyRms = rms(pcm, 0, quarter)
        val lateRms = rms(pcm, pcm.size - quarter, pcm.size)

        assertTrue(frequencyHz in 650.0..710.0)
        assertTrue(lateRms < earlyRms * 0.15)
    }

    @Test
    fun rejectedIndicatorContainsTheConfiguredSilentGap() {
        val pcm = audioIndicatorPcm(AudioIndicator.PttRejected)

        assertTrue(pcm.copyOfRange(samples(70), samples(120)).all { it == 0.toShort() })
        assertTrue(pcm.copyOfRange(0, samples(70)).any { it != 0.toShort() })
        assertTrue(pcm.copyOfRange(samples(120), pcm.size).any { it != 0.toShort() })
    }

    @Test
    fun interruptedIndicatorSweepsDown() {
        val interrupted = audioIndicatorPcm(AudioIndicator.TransmissionInterrupted)

        assertTrue(estimatedFrequencyHz(interrupted.copyOfRange(0, interrupted.size / 2)) >
            estimatedFrequencyHz(interrupted.copyOfRange(interrupted.size / 2, interrupted.size)))
    }

    @Test
    fun queuedIndicatorIsDeterministicDryWoodenTap() {
        val first = audioIndicatorPcm(AudioIndicator.PttQueued)
        val second = audioIndicatorPcm(AudioIndicator.PttQueued)
        val oneMillisecond = samples(1)

        assertArrayEquals(first, second)
        assertEquals(0, first.first().toInt())
        assertEquals(0, first.last().toInt())
        assertTrue(rms(first, 0, oneMillisecond) < rms(first, oneMillisecond, samples(4)))
        assertTrue(rms(first, samples(24), first.size) < rms(first, samples(2), samples(10)) * 0.35)
        assertTrue(spectralEnergy(first, 1_200.0) > spectralEnergy(first, 1_500.0))
        assertTrue(spectralEnergy(first, 1_900.0) > spectralEnergy(first, 1_500.0))
    }

    @Test
    fun everyToneUsesClickFreeEndpoints() {
        AudioIndicator.entries.forEach { indicator ->
            val pcm = audioIndicatorPcm(indicator)
            assertEquals(0, pcm.first().toInt())
            assertEquals(0, pcm.last().toInt())
        }
    }

    private fun samples(durationMs: Int): Int =
        AudioConstants.PLAYBACK_SAMPLE_RATE * durationMs / 1_000

    private fun assertPeakAtMost(pcm: ShortArray, amplitude: Double) {
        val limit = (Short.MAX_VALUE * amplitude).toInt() + 1
        assertTrue(pcm.maxOf { abs(it.toInt()) } <= limit)
    }

    private fun estimatedFrequencyHz(pcm: ShortArray): Double {
        var crossings = 0
        var previous = pcm.first().toInt()
        for (index in 1 until pcm.size) {
            val current = pcm[index].toInt()
            if (current == 0) continue
            if (previous != 0 && ((previous < 0) != (current < 0))) crossings += 1
            previous = current
        }
        return crossings * AudioConstants.PLAYBACK_SAMPLE_RATE.toDouble() / (2.0 * pcm.size)
    }

    private fun rms(pcm: ShortArray, fromIndex: Int, toIndex: Int): Double {
        val meanSquare = pcm
            .sliceArray(fromIndex until toIndex)
            .map { sample -> sample.toDouble() * sample }
            .average()
        return sqrt(meanSquare)
    }

    private fun spectralEnergy(pcm: ShortArray, frequencyHz: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        pcm.forEachIndexed { index, sample ->
            val phase = 2.0 * Math.PI * frequencyHz * index / AudioConstants.PLAYBACK_SAMPLE_RATE
            real += sample * kotlin.math.cos(phase)
            imaginary -= sample * kotlin.math.sin(phase)
        }
        return real * real + imaginary * imaginary
    }
}
