package app.zenptt

import app.zenptt.headset.*

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PttButtonSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveNoticeWaitsForCompletionAndAppearsOnce() {
        var state by mutableStateOf(ChannelUiState())
        var setup by mutableStateOf<HeadsetSetupState?>(HeadsetSetupState(
            step = SetupStep.Test, testComplete = true, confirmed = true,
        ))
        var saves = 0
        val notice = "Headset setup saved. Test it in ECHO with the screen on and locked."
        compose.setContent {
            SettingsScreen(
                state = state,
                hardwareStatus = "",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(),
                appUpdateActions = AppUpdateActions(),
                actions = ChannelActions(savePttSetup = { saves++ }),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
                pttSetup = setup,
            )
        }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle {
            setup = null
            state = state.copy(headsetSetupSaved = 1)
        }
        compose.onAllNodesWithText(notice).assertCountEquals(1)
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText(notice).assertDoesNotExist()
    }

    @Test fun currentConnectionNameIsVisibleThroughoutTestingAndSwitching() {
        var setup by mutableStateOf(HeadsetSetupState(step = SetupStep.Prepare))
        compose.setContent { PttButtonSetupScreen(setup, ChannelActions(), {}) }
        compose.onNodeWithTag("pttSetupSource").assertDoesNotExist()
        for ((source, label) in listOf(HeadsetSource.Spp to "SPP", HeadsetSource.Ble to "BLE",
            HeadsetSource.Media to "Media", HeadsetSource.Hid to "HID")) {
            for (step in listOf(SetupStep.Connecting, SetupStep.Hold, SetupStep.Switching, SetupStep.Test)) {
                compose.runOnIdle { setup = HeadsetSetupState(step = step, source = source) }
                compose.onNodeWithText("Connection: $label").assertIsDisplayed()
            }
        }
    }

    @Test fun pendingSwitchShowsEffectiveSelectionAndDisablesAnotherChange() {
        var changed = 0
        compose.setContent {
            HeadsetSettingsCard(ChannelUiState(headsetChangePending = true),
                ChannelActions(setHeadsetSettings = { changed++ }))
        }
        compose.onNodeWithText("Headset on/off").assertIsDisplayed()
        compose.onNodeWithText("Wait.").assertIsDisplayed()
        compose.onNodeWithTag("headsetToggle").assertIsNotEnabled()
        compose.onNodeWithTag("headsetSetup").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, changed) }
    }

    @Test fun emptyDeviceListShowsConnectAndNoBypass() {
        compose.setContent { PttButtonSetupScreen(HeadsetSetupState(step = SetupStep.Devices), ChannelActions(), {}) }
        compose.onNodeWithText("Connect your headset.").assertIsDisplayed()
        compose.onNodeWithText("My headset is not listed").assertDoesNotExist()
    }

    @Test fun everyStepHasEnglishInstructionsAndCanBeCanceled() {
        var step by mutableStateOf(HeadsetSetupState())
        var canceled = 0
        compose.setContent {
            PttButtonSetupScreen(step, ChannelActions(cancelPttSetup = { canceled++ }), {})
        }
        SetupStep.entries.forEach { current ->
            compose.runOnIdle { step = HeadsetSetupState(step = current) }
            compose.onAllNodesWithText(step.command.presentation.first).onFirst().assertIsDisplayed()
            compose.onNodeWithTag("cancelPttSetup").performClick()
        }
        compose.runOnIdle { assertEquals(SetupStep.entries.size, canceled) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(SetupStep.entries.size + 1, canceled) }
    }

    @Test fun testCannotBeSavedBeforeCompleteCycleAndTimeoutOffersRetry() {
        var setup by mutableStateOf(HeadsetSetupState(step = SetupStep.Test))
        var saved = 0
        var retried = 0
        compose.setContent {
            PttButtonSetupScreen(setup, ChannelActions(retryPttSetup = { retried++ }), { saved++ })
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { setup = setup.copy(testComplete = true, testOn = true) }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { setup = setup.copy(testOn = false) }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { setup = setup.copy(confirmed = true) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(1, saved)
            setup = setup.copy(step = SetupStep.Error, error = "Could not detect the PTT button. Your previous setup is unchanged.")
        }
        compose.onNodeWithTag("retryPttSetup").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun cancelRestoresSelectionAndResetUndoRestoresWholeAssignment() {
        val initial = HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Toggle))
        var settings by mutableStateOf(initial)
        var setup by mutableStateOf<HeadsetSetupState?>(null)
        compose.setContent {
            ZenPttApp(
                state = ChannelUiState(headsetSettings = settings),
                pttSetup = setup,
                actions = ChannelActions(
                    setHeadsetSettings = { settings = it },
                    beginPttSetup = { setup = HeadsetSetupState() },
                    cancelPttSetup = { setup = null },
                ),
            )
        }
        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithTag("headsetSetup").performScrollTo().performClick()
        compose.onNodeWithText("Connect your headset.").assertIsDisplayed()
        compose.onNodeWithTag("cancelPttSetup").performClick()
        compose.onNodeWithTag("headsetToggle").assertIsDisplayed()
        compose.runOnIdle { assertEquals(initial, settings) }
        compose.onNodeWithTag("resetSettings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(HeadsetSettings(), settings) }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(initial, settings) }
    }
}
