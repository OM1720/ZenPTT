package app.zenptt.headset

import org.junit.Assert.*
import org.junit.Test

class ButtonInputTest {
    private val rule = ButtonRule(RuleKind.Key, keyCode = 85)
    private fun key(time: Long, down: Boolean, id: Long = time) = Observation(time, "key:85", keyCode = 85, pressed = down, pressId = id)

    @Test fun holdDeduplicatesAndTerminalResetRequiresANewPress() {
        val input = ButtonInput()
        val down = key(100, true)
        assertEquals(true, input.event(down, rule, ButtonBehavior.Hold))
        assertNull(input.event(down, rule, ButtonBehavior.Hold))
        assertTrue(input.reset())
        assertNull(input.event(down.copy(time = 200), rule, ButtonBehavior.Hold))
        assertNull(input.event(key(300, false, 100), rule, ButtonBehavior.Hold))
        assertEquals(true, input.event(key(400, true), rule, ButtonBehavior.Hold))
        assertEquals(false, input.event(key(600, false, 400), rule, ButtonBehavior.Hold))
    }

    @Test fun toggleUsesNewPressesAndIgnoresReleaseAndWrongKeys() {
        val input = ButtonInput()
        assertNull(input.event(key(10, true).copy(keyCode = 126), rule, ButtonBehavior.Toggle))
        assertEquals(true, input.event(key(100, true), rule, ButtonBehavior.Toggle))
        assertNull(input.event(key(110, false, 100), rule, ButtonBehavior.Toggle))
        assertEquals(false, input.event(key(200, true), rule, ButtonBehavior.Toggle))
        assertNull(input.event(key(210, true, 200), rule, ButtonBehavior.Toggle))
    }

    @Test fun stateNotificationsCannotRestartTransmissionAfterReset() {
        val input = ButtonInput()
        val bit = ButtonRule(RuleKind.Bit, size = 1, mask = 1, pressedValue = 1)
        fun packet(time: Long, value: String) = Observation(time, "buttons", value)
        assertEquals(true, input.event(packet(100, "01"), bit, ButtonBehavior.Hold))
        assertNull(input.event(packet(200, "81"), bit, ButtonBehavior.Hold))
        input.reset()
        assertNull(input.event(packet(300, "01"), bit, ButtonBehavior.Hold))
        assertNull(input.event(packet(400, "00"), bit, ButtonBehavior.Hold))
        assertEquals(true, input.event(packet(500, "01"), bit, ButtonBehavior.Hold))
        assertEquals(false, input.event(packet(600, "00"), bit, ButtonBehavior.Hold))
    }

    @Test fun reversedAndStaleKeyEdgesCannotStartOrReleaseAnotherPress() {
        val input = ButtonInput()
        assertNull(input.event(key(110, false, 100), rule, ButtonBehavior.Hold))
        assertNull(input.event(key(120, true, 100), rule, ButtonBehavior.Hold))
        assertEquals(true, input.event(key(200, true), rule, ButtonBehavior.Hold))
        assertNull(input.event(key(210, false, 100), rule, ButtonBehavior.Hold))
        assertTrue(input.engaged)
        assertEquals(false, input.event(key(220, false, 200), rule, ButtonBehavior.Hold))
        assertNull(input.event(key(230, true, 100), rule, ButtonBehavior.Hold))
        assertEquals(true, input.event(key(300, true), rule, ButtonBehavior.Hold))
    }

    @Test fun isolatedPulseDebouncesDuplicatesButAllowsTheNextAttempt() {
        val input = ButtonInput()
        val pulse = ButtonRule(RuleKind.Pulse, down = "01")
        assertEquals(true, input.event(Observation(100, "button", "01"), pulse, ButtonBehavior.Toggle))
        assertNull(input.event(Observation(110, "button", "01"), pulse, ButtonBehavior.Toggle))
        input.reset()
        assertEquals(true, input.event(Observation(400, "button", "01"), pulse, ButtonBehavior.Toggle))
    }
}
