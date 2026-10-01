package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFramePacerTest {
    @Test
    fun earlyFramesWaitForTheirMediaTime() {
        val pacer = AudioFramePacer(frameDurationMs = 20)

        assertEquals(0L, pacer.delayBeforeFrame(nowMs = 100))
        assertEquals(15L, pacer.delayBeforeFrame(nowMs = 105))
        assertEquals(20L, pacer.delayBeforeFrame(nowMs = 120))
    }

    @Test
    fun lateFrameRestartsPacingWithoutCatchUpBurst() {
        val pacer = AudioFramePacer(frameDurationMs = 20)

        assertEquals(0L, pacer.delayBeforeFrame(nowMs = 100))
        assertEquals(0L, pacer.delayBeforeFrame(nowMs = 150))
        assertEquals(15L, pacer.delayBeforeFrame(nowMs = 155))
    }
}
