package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPowerSaveStateTest {
    @Test
    fun pttAndIncomingAudioResetIdleTimeout() {
        var now = 0L
        val state = AudioPowerSaveState { now }
        state.setActive(true)

        now = 900
        assertFalse(state.tryEnterSleep(1_000, audioBusy = false))
        state.activity(1_000, doubleToneOnWake = true)
        now = 1_800
        assertFalse(state.tryEnterSleep(1_000, audioBusy = false))
        state.activity(1_000, doubleToneOnWake = false)
        now = 2_800
        assertTrue(state.tryEnterSleep(1_000, audioBusy = false))
    }

    @Test
    fun activeCaptureOrPlaybackPreventsSleep() {
        var now = 0L
        val state = AudioPowerSaveState { now }
        state.setActive(true)
        now = 1_000

        assertFalse(state.tryEnterSleep(500, audioBusy = true))
        assertTrue(state.tryEnterSleep(500, audioBusy = false))
    }

    @Test
    fun pttWakeUsesTwoTonesThenReturnsToOne() {
        var now = 0L
        val state = AudioPowerSaveState { now }
        state.setActive(true)
        now = 1_000
        assertTrue(state.tryEnterSleep(500, audioBusy = false))

        assertTrue(state.activity(500, doubleToneOnWake = true))
        assertEquals(2, state.consumeGrantToneCount())
        assertEquals(1, state.consumeGrantToneCount())
    }

    @Test
    fun incomingWakeAndReleaseNeverLeaveDoubleTonePending() {
        var now = 0L
        val state = AudioPowerSaveState { now }
        state.setActive(true)
        now = 1_000
        state.tryEnterSleep(500, audioBusy = false)

        assertTrue(state.activity(500, doubleToneOnWake = false))
        assertEquals(1, state.consumeGrantToneCount())

        now = 2_000
        state.tryEnterSleep(500, audioBusy = false)
        state.activity(500, doubleToneOnWake = true)
        state.clearDoubleTone()
        assertEquals(1, state.consumeGrantToneCount())
    }

    @Test
    fun pttDetectsExpiredIdleWhenMonitorWasSuspended() {
        var now = 0L
        val state = AudioPowerSaveState { now }
        state.setActive(true)
        now = 1_000

        assertTrue(state.activity(500, doubleToneOnWake = true))
        assertEquals(2, state.consumeGrantToneCount())
        assertFalse(state.isSleeping())
    }

    @Test
    fun pttBeforeIdleTimeoutStaysAwakeWithOneTone() {
        var now = 0L
        val state = AudioPowerSaveState { now }
        state.setActive(true)
        now = 499

        assertFalse(state.activity(500, doubleToneOnWake = true))
        assertEquals(1, state.consumeGrantToneCount())
    }
}
