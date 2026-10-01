package app.zenptt.headset

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupCommandTest {
    @Test fun emptySelectionRequestsConnectionAndConnectedSelectionRequestsChoice() {
        val state = HeadsetSetupState(step = SetupStep.Devices)
        assertEquals(SetupCommand.Connect, state.command)
        assertEquals(SetupCommand.Select, state.copy(devices = listOf(HeadsetDevice("connected", "Headset"))).command)
    }

    @Test fun holdTestShowsTheRequiredPhysicalActionThenConfirmation() {
        val state = HeadsetSetupState(step = SetupStep.Test, behavior = ButtonBehavior.Hold)
        assertEquals(SetupCommand.Hold, state.command)
        assertEquals(SetupCommand.Release, state.copy(testOn = true).command)
        assertEquals(SetupCommand.CheckIndicator, state.copy(testComplete = true).command)
    }

    @Test fun toggleTestDistinguishesFirstAndSecondPressAndPendingSave() {
        val state = HeadsetSetupState(step = SetupStep.Test, behavior = ButtonBehavior.Toggle)
        assertEquals(SetupCommand.Press, state.command)
        assertEquals(SetupCommand.PressAgain, state.copy(testOn = true).command)
        assertEquals(SetupCommand.CheckIndicator, state.copy(testComplete = true).command)
        assertEquals(SetupCommand.Wait, state.copy(testComplete = true, saving = true).command)
    }

    @Test fun everyTrainingRoundUsesTheSameDirectInstruction() {
        for (round in 1..5) {
            assertEquals(SetupCommand.Hold, HeadsetSetupState(step = SetupStep.Hold, round = round).command)
            assertEquals(SetupCommand.Release, HeadsetSetupState(step = SetupStep.Release, round = round).command)
        }
    }

    @Test fun waitingNeverAsksTheUserToWaitForAnActionTheyMustTake() {
        assertEquals(SetupCommand.Wait, HeadsetSetupState(step = SetupStep.Connecting).command)
        assertEquals(SetupCommand.Continue, HeadsetSetupState(step = SetupStep.Switching).command)
        assertEquals(SetupCommand.Retry, HeadsetSetupState(step = SetupStep.Error).command)
    }
}
