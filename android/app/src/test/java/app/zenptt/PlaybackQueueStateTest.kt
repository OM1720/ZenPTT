package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueStateTest {
    @Test
    fun completedGenerationClearsPhantomFrames() {
        val state = PlaybackQueueState(1500)
        val generation = state.transmissionStarted()
        repeat(121) { assertEquals(generation, state.reserveFrame()) }
        repeat(120) { state.frameConsumed(generation) }

        assertEquals(generation, state.transmissionEnded())
        state.generationFinished(generation)

        assertEquals(0, state.snapshot().queuedFrames)
        assertEquals(121, state.snapshot().maxQueuedFrames)
        assertFalse(state.hasQueuedFrames())
    }

    @Test
    fun finishingOldGenerationPreservesNextTransmission() {
        val state = PlaybackQueueState(10)
        val oldGeneration = state.transmissionStarted()
        assertEquals(oldGeneration, state.reserveFrame())
        state.transmissionEnded()
        val newGeneration = state.transmissionStarted()
        assertEquals(newGeneration, state.reserveFrame())

        state.generationFinished(oldGeneration)

        assertTrue(state.hasQueuedFrames())
        assertEquals(1, state.snapshot().queuedFrames)
        state.frameConsumed(newGeneration)
        assertFalse(state.hasQueuedFrames())
    }

    @Test
    fun framesAfterEndAreRejectedUntilNextStart() {
        val state = PlaybackQueueState(2)
        state.transmissionStarted()
        state.transmissionEnded()

        assertNull(state.reserveFrame())

        val generation = state.transmissionStarted()
        assertEquals(generation, state.reserveFrame())
    }

    @Test
    fun capacityIsSharedAcrossGenerations() {
        val state = PlaybackQueueState(2)
        val oldGeneration = state.transmissionStarted()
        assertEquals(oldGeneration, state.reserveFrame())
        state.transmissionEnded()
        val newGeneration = state.transmissionStarted()
        assertEquals(newGeneration, state.reserveFrame())

        assertNull(state.reserveFrame())
    }
}
