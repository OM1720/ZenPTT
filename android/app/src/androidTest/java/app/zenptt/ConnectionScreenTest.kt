package app.zenptt

import app.zenptt.headset.*

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mainScreenContainsOnlyPrimaryControls() {
        composeRule.setContent {
            MainScreen(ChannelUiState(channelCode = "ROOM1"), ChannelActions())
        }

        composeRule.onNodeWithTag("connectionToggle").assertIsDisplayed()
        composeRule.onNodeWithText("ZenPTT").assertIsDisplayed()
        composeRule.onNodeWithTag("openSettings").assertIsDisplayed()
        composeRule.onNodeWithTag("frequency").assertIsDisplayed()
        composeRule.onNodeWithTag("ptt").assertIsDisplayed()
        composeRule.onNodeWithText("ECHO test").assertDoesNotExist()
        composeRule.onNodeWithText("Send debug info").assertDoesNotExist()
    }

    @Test
    fun connectionControlKeepsActionDescriptionsAcrossStates() {
        var state by mutableStateOf(ChannelUiState())
        composeRule.setContent { MainScreen(state, ChannelActions()) }

        composeRule.onNodeWithTag("connectionToggle").assertContentDescriptionEquals("Connect")
        composeRule.runOnIdle {
            state = state.copy(currentChannel = "ROOM1", status = SessionStatus.Ready)
        }
        composeRule.onNodeWithTag("connectionToggle").assertContentDescriptionEquals("Disconnect")
        composeRule.runOnIdle { state = state.copy(status = SessionStatus.ConnectionError) }
        composeRule.onNodeWithTag("connectionToggle").assertContentDescriptionEquals("Reconnect")
    }

    @Test
    fun frequencyDoneNormalizesAndSubmitsEcho() {
        var submitted = ""
        composeRule.setContent {
            MainScreen(
                ChannelUiState(channelCode = "ROOM1"),
                ChannelActions(submitFrequency = {
                    submitted = it
                    InputValidator.channelCode(it) != null
                }),
            )
        }

        composeRule.onNodeWithTag("frequency").performTextReplacement("echo")
        composeRule.onNodeWithTag("frequency").performImeAction()
        composeRule.runOnIdle { assertEquals("ECHO", submitted) }
    }

    @Test
    fun participantCountShowsLiveValueFallbackAndEchoValue() {
        var state by mutableStateOf(
            ChannelUiState(
                channelCode = "ROOM1",
                currentChannel = "ROOM1",
                participantCount = 2,
                status = SessionStatus.Ready,
            ),
        )
        composeRule.setContent { MainScreen(state, ChannelActions()) }

        composeRule.onNodeWithTag("participantCount")
            .assertContentDescriptionEquals("2 active connections")
        composeRule.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(status = SessionStatus.Reconnecting) }
        composeRule.onNodeWithTag("participantCount")
            .assertContentDescriptionEquals("Active connections unavailable")
        composeRule.onNodeWithText("—", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(
                channelCode = ECHO_CHANNEL,
                currentChannel = ECHO_CHANNEL,
                participantCount = null,
                status = SessionStatus.Ready,
            )
        }
        composeRule.onNodeWithTag("participantCount")
            .assertContentDescriptionEquals("Active connections unavailable")
        composeRule.onNodeWithText("—", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(participantCount = 2) }
        composeRule.onNodeWithTag("participantCount")
            .assertContentDescriptionEquals("2 active connections")
        composeRule.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun participantCountIsHiddenForChangedFrequencyDraft() {
        composeRule.setContent {
            MainScreen(
                ChannelUiState(
                    channelCode = "ROOM1",
                    currentChannel = "ROOM1",
                    participantCount = 3,
                    status = SessionStatus.Ready,
                ),
                ChannelActions(),
            )
        }

        composeRule.onNodeWithTag("frequency").performTextReplacement("ROOM2")

        composeRule.onNodeWithTag("participantCount")
            .assertContentDescriptionEquals("Active connections unavailable")
        composeRule.onNodeWithText("—", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun frequencyHistorySelectionSubmits() {
        var submitted = ""
        composeRule.setContent {
            MainScreen(
                ChannelUiState(
                    channelCode = "ROOM1",
                    frequencyChoices = listOf("ROOM1", ECHO_CHANNEL),
                ),
                ChannelActions(submitFrequency = {
                    submitted = it
                    true
                }),
            )
        }

        composeRule.onNodeWithTag("frequency").performClick()
        composeRule.onNodeWithTag("frequencyChoice:$ECHO_CHANNEL").performClick()

        composeRule.runOnIdle { assertEquals(ECHO_CHANNEL, submitted) }
        composeRule.onNodeWithTag("frequency").assertTextContains(ECHO_CHANNEL)
    }
    @Test
    fun settingsResetAndHomeApplyOnlySecondScreenDefaults() {
        var appliedServer = ""
        var appliedPower = ""
        var consumedError = -1
        composeRule.setContent {
            ZenPttApp(
                state = ChannelUiState(
                    serverAddress = "wss://custom.example",
                    channelCode = "ROOM9",
                    powerSaveTimeoutMinutes = "30",
                    uiError = UiError(7, UiErrorTarget.Server, "Old server error"),
                ),
                actions = ChannelActions(
                    consumeUiError = { consumedError = it },
                    applySettings = { server, power ->
                        appliedServer = server
                        appliedPower = power
                        true
                    },
                ),
            )
        }

        composeRule.onNodeWithTag("openSettings").performClick()
        composeRule.onNodeWithTag("resetSettings").performClick()
        composeRule.onNodeWithTag("returnHome").performClick()

        composeRule.runOnIdle {
            assertEquals(DEFAULT_SERVER_ADDRESS, appliedServer)
            assertEquals(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES.toString(), appliedPower)
            assertEquals(7, consumedError)
        }
        composeRule.onNodeWithTag("frequency").assertTextContains("ROOM9")
    }

    @Test
    fun rejectedSettingsKeepSettingsScreenOpen() {
        var applies = 0
        composeRule.setContent {
            ZenPttApp(
                state = ChannelUiState(
                    serverAddress = DEFAULT_SERVER_ADDRESS,
                    powerSaveTimeoutMinutes = "10",
                ),
                actions = ChannelActions(applySettings = { _, _ -> applies += 1; false }),
            )
        }

        composeRule.onNodeWithTag("openSettings").performClick()
        composeRule.onNodeWithTag("serverAddress").performTextReplacement("invalid")
        composeRule.onNodeWithTag("returnHome").performClick()

        composeRule.onNodeWithTag("returnHome").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, applies) }
    }

    @Test
    fun diagnosticsExposeCopyAndShareActions() {
        var sends = 0
        var copies = 0
        var shares = 0
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(
                    diagnosticUploadStatus = "Report sent",
                    diagnosticReportCode = "260715-K7M4",
                ),
                hardwareStatus = "BM008: connected",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(),
                appUpdateActions = AppUpdateActions(),
                actions = ChannelActions(
                    sendDiagnosticReport = { sends += 1 },
                    copyDiagnosticCode = { copies += 1 },
                    shareDebugInfo = { shares += 1 },
                ),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("diagnosticsCard").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnosticReportCode", useUnmergedTree = true)
            .assertTextContains("260715-K7M4")
        composeRule.onNodeWithTag("sendDiagnosticReport").performClick()
        composeRule.onNodeWithTag("copyDiagnosticCode").performClick()
        composeRule.onNodeWithTag("shareDebugInfo").performClick()
        composeRule.onNodeWithTag("shareDebugInfo").performClick()
        composeRule.onNodeWithText("Code copied").assertIsDisplayed()
        composeRule.onNodeWithText("ECHO test").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, sends)
            assertEquals(1, copies)
            assertEquals(2, shares)
        }
    }

    @Test
    fun diagnosticSendRetryAndUploadingKeepTheirActions() {
        var sends = 0
        var state by mutableStateOf(ChannelUiState())
        composeRule.setContent {
            SettingsScreen(
                state = state,
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(),
                appUpdateActions = AppUpdateActions(),
                actions = ChannelActions(sendDiagnosticReport = { sends += 1 }),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("sendDiagnosticReport").performScrollTo().performClick()
        composeRule.onNodeWithText("Send debug info").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, sends)
            state = state.copy(diagnosticUploadStatus = "Unable to send diagnostics")
        }
        composeRule.onNodeWithText("Retry diagnostics").assertIsDisplayed()
        composeRule.onNodeWithTag("sendDiagnosticReport").performClick()
        composeRule.runOnIdle {
            assertEquals(2, sends)
            state = state.copy(
                diagnosticUploadStatus = "Report sent",
                diagnosticReportCode = "260715-K7M4",
            )
        }
        composeRule.onNodeWithTag("sendDiagnosticReport").assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(3, sends)
            state = state.copy(
                diagnosticUploading = true,
                diagnosticUploadStatus = null,
                diagnosticReportCode = null,
            )
        }
        composeRule.onNodeWithText("Sending diagnostics\u2026").assertIsDisplayed()
        composeRule.onNodeWithTag("sendDiagnosticReport")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun diagnosticAndShareTitlesStayVerticallyAlignedAfterUpload() {
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(
                    diagnosticUploadStatus = "Report sent",
                    diagnosticReportCode = "260715-K7M4",
                ),
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(),
                appUpdateActions = AppUpdateActions(),
                actions = ChannelActions(),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("diagnosticsCard").performScrollTo()
        val sendTop = composeRule.onNodeWithTag("diagnosticSendTitle", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        val shareTop = composeRule.onNodeWithTag("shareDebugInfo", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        assertEquals(sendTop, shareTop, 0.1f)
    }

    @Test
    fun diagnosticCopyRemainsAvailableOnNarrowLargeTextLayout() {
        var copies = 0
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                fontScale = 1.3f
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                Box(Modifier.width(360.dp).height(800.dp)) {
                    SettingsScreen(
                        state = ChannelUiState(
                            diagnosticUploadStatus = "Report sent",
                            diagnosticReportCode = "260715-K7M4",
                        ),
                        hardwareStatus = "BM008: connected",
                        showBatteryOptimizationWarning = false,
                        appUpdateState = AppUpdateUiState(),
                        appUpdateActions = AppUpdateActions(),
                        actions = ChannelActions(copyDiagnosticCode = { copies += 1 }),
                        serverAddress = DEFAULT_SERVER_ADDRESS,
                        onServerAddressChange = {},
                        powerSaveTimeoutMinutes = "10",
                        onPowerSaveTimeoutChange = {},
                        onReturnHome = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("copyDiagnosticCode")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, copies) }
    }

    @Test
    fun savedPttSetupIsOffByDefaultAndAppliesImmediately() {
        var enabled by mutableStateOf(false)
        composeRule.setContent {
            ZenPttApp(
                state = ChannelUiState(headsetSettings = HeadsetSettings(enabled = enabled)),
                actions = ChannelActions(setHeadsetSettings = { enabled = it.enabled }),
            )
        }

        composeRule.onNodeWithTag("openSettings").performClick()
        composeRule.onNodeWithTag("headsetToggle").performClick()
        composeRule.runOnIdle { assertEquals(true, enabled) }

        composeRule.onNodeWithTag("headsetToggle").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(false, enabled) }
    }
    @Test
    fun serviceSoundsSettingIsNotShown() {
        composeRule.setContent { ZenPttApp(state = ChannelUiState(), actions = ChannelActions()) }

        composeRule.onNodeWithTag("openSettings").performClick()

        composeRule.onNodeWithText("Service sounds").assertDoesNotExist()
        composeRule.onNodeWithTag("serviceSoundsCard").assertDoesNotExist()
    }

    @Test
    fun updateRowAutoChecksAndInstallsInPlace() {
        var checks = 0
        var installs = 0
        var updateState by mutableStateOf(AppUpdateUiState())
        val release = AppReleaseInfo(14, "0.5.0", "a".repeat(64), 123)
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(),
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = updateState,
                appUpdateActions = AppUpdateActions(
                    check = { checks += 1; updateState = AppUpdateUiState(available = release) },
                    downloadAndInstall = { installs += 1 },
                ),
                actions = ChannelActions(),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("installUpdate").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, checks)
            assertEquals(1, installs)
        }
    }

    @Test
    fun settingsAutoChecksOncePerEntry() {
        var checks = 0
        var updateState by mutableStateOf(AppUpdateUiState())
        composeRule.setContent {
            ZenPttApp(
                state = ChannelUiState(
                    serverAddress = DEFAULT_SERVER_ADDRESS,
                    powerSaveTimeoutMinutes = "10",
                ),
                appUpdateState = updateState,
                appUpdateActions = AppUpdateActions(check = {
                    checks += 1
                    updateState = AppUpdateUiState(status = "0.7.2")
                }),
                actions = ChannelActions(),
            )
        }

        composeRule.onNodeWithTag("openSettings").performClick()
        composeRule.runOnIdle { assertEquals(1, checks) }
        composeRule.onNodeWithTag("checkForUpdates").performScrollTo()
        composeRule.onNodeWithText("0.7.2").assertIsDisplayed()
        composeRule.onNodeWithTag("returnHome").performScrollTo().performClick()
        composeRule.onNodeWithTag("openSettings").performClick()
        composeRule.runOnIdle { assertEquals(2, checks) }
    }

    @Test
    fun updateRowSupportsManualCheckAfterAutoCheck() {
        var checks = 0
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(),
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(status = "0.7.2"),
                appUpdateActions = AppUpdateActions(check = { checks += 1 }),
                actions = ChannelActions(),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.runOnIdle { assertEquals(1, checks) }
        composeRule.onNodeWithTag("checkForUpdates").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(2, checks) }
    }

    @Test
    fun checkingUpdateLeavesOtherSettingsInteractive() {
        var server by mutableStateOf(DEFAULT_SERVER_ADDRESS)
        var powerSave by mutableStateOf("10")
        var bm008Enabled by mutableStateOf(false)
        var checks = 0
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(headsetSettings = HeadsetSettings(enabled = bm008Enabled)),
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(
                    checking = true,
                    status = "Checking for updates...",
                ),
                appUpdateActions = AppUpdateActions(check = { checks += 1 }),
                actions = ChannelActions(setHeadsetSettings = { bm008Enabled = it.enabled }),
                serverAddress = server,
                onServerAddressChange = { server = it },
                powerSaveTimeoutMinutes = powerSave,
                onPowerSaveTimeoutChange = { powerSave = it },
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("checkForUpdates")
            .performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithTag("serverAddress")
            .performTextReplacement("wss://other.example")
        composeRule.onNodeWithTag("powerSaveTimeout")
            .performScrollTo()
            .performTextReplacement("30")
        composeRule.onNodeWithTag("headsetToggle").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(0, checks)
            assertEquals("wss://other.example", server)
            assertEquals("30", powerSave)
            assertTrue(bm008Enabled)
        }
    }

    @Test
    fun downloadingUpdateExposesProgressAndBlocksClicks() {
        val release = AppReleaseInfo(14, "0.5.0", "a".repeat(64), 123)
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(),
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = AppUpdateUiState(
                    downloading = true,
                    available = release,
                    status = "Downloading update · 50% · ETA 10s",
                    progress = 0.5f,
                ),
                appUpdateActions = AppUpdateActions(),
                actions = ChannelActions(),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("installUpdate")
            .performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f, 0),
                ),
            )
        composeRule.onNodeWithText("Downloading update…").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading update · 50% · ETA 10s").assertIsDisplayed()
    }

    @Test
    fun completedUpdateRestoresInstallClick() {
        var installs = 0
        var updateState by mutableStateOf<AppUpdateUiState>(AppUpdateUiState())
        val release = AppReleaseInfo(14, "0.5.0", "a".repeat(64), 123)
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(),
                hardwareStatus = "BM008: inactive",
                showBatteryOptimizationWarning = false,
                appUpdateState = updateState,
                appUpdateActions = AppUpdateActions(downloadAndInstall = { installs += 1 }),
                actions = ChannelActions(),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.runOnIdle {
            updateState = AppUpdateUiState(
                downloading = true,
                available = release,
                status = "Downloading update · 0%",
                progress = 0f,
            )
        }
        composeRule.onNodeWithTag("installUpdate")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0f, 0f..1f, 0),
                ),
            )
        composeRule.runOnIdle {
            updateState = updateState.copy(
                status = "Downloading update · 50%",
                progress = 0.5f,
            )
        }
        composeRule.onNodeWithTag("installUpdate").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.5f, 0f..1f, 0),
            ),
        )
        composeRule.runOnIdle {
            updateState = updateState.copy(
                status = "Downloading update · 100%",
                progress = 1f,
            )
        }
        composeRule.onNodeWithTag("installUpdate").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(1f, 0f..1f, 0),
            ),
        )
        composeRule.runOnIdle {
            updateState = AppUpdateUiState(
                available = release,
                status = "Update verified. Opening installer...",
            )
        }
        composeRule.onNodeWithTag("installUpdate").assertHasClickAction()
        composeRule.onNodeWithTag("installUpdate").performClick()
        composeRule.runOnIdle { assertEquals(1, installs) }
    }

    @Test
    fun offlineTouchPttCanStartAndCancelPendingConnection() {
        var downs = 0
        var ups = 0
        composeRule.setContent {
            MainScreen(
                ChannelUiState(channelCode = ECHO_CHANNEL),
                ChannelActions(pttDown = { downs++ }, pttUp = { ups++ }),
            )
        }

        composeRule.onNodeWithTag("ptt").performTouchInput { down(center); up() }

        composeRule.runOnIdle {
            assertEquals(1, downs)
            assertEquals(1, ups)
        }
    }
    @Test
    fun pttReleaseSurvivesGrantRecomposition() {
        var state by mutableStateOf(channelState(SessionStatus.Ready))
        var downs = 0
        var ups = 0
        composeRule.setContent {
            MainScreen(
                state,
                ChannelActions(pttDown = { downs += 1 }, pttUp = { ups += 1 }),
            )
        }

        composeRule.onNodeWithTag("ptt").performTouchInput { down(center) }
        composeRule.runOnIdle { state = channelState(SessionStatus.Transmitting) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ptt").performTouchInput { up() }

        composeRule.runOnIdle {
            assertEquals(1, downs)
            assertEquals(1, ups)
        }
    }

    @Test
    fun headsetControlsRemainAvailableWithoutLegacyStatusRows() {
        composeRule.setContent {
            SettingsScreen(
                state = ChannelUiState(),
                hardwareStatus = "BM008: connected",
                showBatteryOptimizationWarning = true,
                appUpdateState = AppUpdateUiState(),
                appUpdateActions = AppUpdateActions(),
                actions = ChannelActions(),
                serverAddress = DEFAULT_SERVER_ADDRESS,
                onServerAddressChange = {},
                powerSaveTimeoutMinutes = "10",
                onPowerSaveTimeoutChange = {},
                onReturnHome = {},
            )
        }

        composeRule.onNodeWithTag("headsetToggle").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("headsetSetup").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
        composeRule.onNodeWithText("Audio").assertDoesNotExist()
        composeRule.onNodeWithText("Bluetooth headset").assertDoesNotExist()
        composeRule.onNodeWithTag("batteryOptimizationSettings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun frequencyErrorIsInlineAccessibleAndFocused() {
        var state by mutableStateOf(ChannelUiState())
        composeRule.setContent {
            MainScreen(
                state,
                ChannelActions(submitFrequency = { value ->
                    if (value == ECHO_CHANNEL) {
                        state = state.copy(uiError = null)
                        true
                    } else {
                        state = state.copy(
                            uiError = UiError(1, UiErrorTarget.Frequency, "Enter a frequency"),
                        )
                        false
                    }
                }),
            )
        }

        composeRule.onNodeWithTag("frequency").performImeAction()

        composeRule.onNodeWithText("Enter a frequency").assertIsDisplayed()
        composeRule.onNodeWithTag("frequency")
            .assertIsFocused()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithTag("frequencyChoices").assertIsDisplayed()

        composeRule.onNodeWithTag("frequencyChoice:$ECHO_CHANNEL").performClick()

        composeRule.onNodeWithText("Enter a frequency").assertDoesNotExist()
        composeRule.onNodeWithTag("frequency").assertTextContains(ECHO_CHANNEL)
        composeRule.onNodeWithTag("frequencyChoices").assertDoesNotExist()
    }

    @Test
    fun talkBackClickTogglesWhileTouchStillUsesHoldCallbacks() {
        var state by mutableStateOf(channelState(SessionStatus.Ready))
        var accessibleDowns = 0
        var accessibleUps = 0
        var touchDowns = 0
        var touchUps = 0
        composeRule.setContent {
            MainScreen(
                state,
                ChannelActions(
                    pttDown = { touchDowns++ },
                    pttUp = { touchUps++ },
                    toggleAccessiblePtt = {
                        if (state.status == SessionStatus.Ready) {
                            accessibleDowns++
                            state = state.copy(status = SessionStatus.Requesting)
                        } else {
                            accessibleUps++
                            state = state.copy(status = SessionStatus.Ready)
                        }
                    },
                ),
            )
        }

        composeRule.onNodeWithTag("ptt").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ptt").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ptt").performTouchInput { down(center); up() }

        composeRule.runOnIdle {
            assertEquals(1, accessibleDowns)
            assertEquals(1, accessibleUps)
            assertEquals(1, touchDowns)
            assertEquals(1, touchUps)
        }
    }

    @Test
    fun offlineTalkBackDescriptionUsesUnknownMetrics() {
        composeRule.setContent { MainScreen(ChannelUiState(), ChannelActions()) }

        composeRule.onNodeWithTag("ptt").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Offline, quality good, RTT unknown, PTT unknown, gaps unknown",
            ),
        )
    }

    @Test
    fun queuedPttSnackbarIsIndefiniteUntilNoticeIsCleared() {
        var state by mutableStateOf(
            channelState(SessionStatus.Reconnecting).copy(
                pttNotice = PttNotice(
                    id = 7,
                    kind = PttNoticeKind.Queued,
                    message = "PTT queued — keep holding and wait for the grant tone.",
                ),
            ),
        )
        var consumed = -1
        composeRule.setContent {
            ZenPttApp(
                state = state,
                actions = ChannelActions(consumePttNotice = { consumed = it }),
            )
        }

        composeRule.onNodeWithText(
            "PTT queued — keep holding and wait for the grant tone.",
        ).assertIsDisplayed()
        Thread.sleep(500)
        composeRule.runOnIdle { assertEquals(-1, consumed) }

        composeRule.runOnIdle { state = state.copy(pttNotice = null) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "PTT queued — keep holding and wait for the grant tone.",
        ).assertDoesNotExist()
    }

    @Test
    fun rejectedPttSnackbarUsesLongDurationAndConsumesItsIdentifier() {
        val message = "Channel busy — release and press PTT again after the channel-free tone."
        var consumed = -1
        composeRule.setContent {
            ZenPttApp(
                state = channelState(SessionStatus.Busy).copy(
                    pttNotice = PttNotice(9, PttNoticeKind.Rejected, message),
                ),
                actions = ChannelActions(consumePttNotice = { consumed = it }),
            )
        }

        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(-1, consumed) }
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.runOnIdle { assertEquals(9, consumed) }
    }

    @Test
    fun talkBackDescriptionIncludesQueuedAndRejectedPttInstructions() {
        val queued = "PTT queued — keep holding and wait for the grant tone."
        var state by mutableStateOf(
            channelState(SessionStatus.Reconnecting).copy(
                pttNotice = PttNotice(1, PttNoticeKind.Queued, queued),
            ),
        )
        composeRule.setContent { MainScreen(state, ChannelActions()) }

        composeRule.onNodeWithTag("ptt").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Reconnecting, quality good, RTT unknown, PTT unknown, gaps unknown, $queued",
            ),
        )

        val rejected = "PTT unavailable — release and press PTT again."
        composeRule.runOnIdle {
            state = channelState(SessionStatus.Busy).copy(
                pttNotice = PttNotice(2, PttNoticeKind.Rejected, rejected),
            )
        }
        composeRule.onNodeWithTag("ptt").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Channel busy, quality good, RTT unknown, PTT unknown, gaps unknown, $rejected",
            ),
        )
    }

    @Test
    fun serverDraftEditClearsPingAndUpdateResults() {
        var state by mutableStateOf(
            ChannelUiState(
                serverAddress = DEFAULT_SERVER_ADDRESS,
                powerSaveTimeoutMinutes = "10",
                serverCheckStatus = "Server available",
            ),
        )
        var updateState by mutableStateOf(AppUpdateUiState(status = "ZenPTT is up to date"))
        composeRule.setContent {
            ZenPttApp(
                state = state,
                appUpdateState = updateState,
                actions = ChannelActions(serverDraftChanged = {
                    state = state.copy(serverCheckStatus = null)
                    updateState = AppUpdateUiState()
                }),
            )
        }

        composeRule.onNodeWithTag("openSettings").performClick()
        composeRule.onNodeWithTag("serverAddress").performScrollTo()
        composeRule.onNodeWithText("Server available").assertIsDisplayed()
        composeRule.onNodeWithTag("checkForUpdates").performScrollTo()
        composeRule.onNodeWithText("ZenPTT is up to date").assertIsDisplayed()
        composeRule.onNodeWithTag("serverAddress").performScrollTo()
            .performTextReplacement("wss://draft.example")
        composeRule.onNodeWithText("Server available").assertDoesNotExist()
        composeRule.onNodeWithText("ZenPTT is up to date").assertDoesNotExist()
    }

    private fun channelState(status: SessionStatus) = ChannelUiState(
        currentChannel = "ROOM1",
        channelCode = "ROOM1",
        status = status,
    )
}
