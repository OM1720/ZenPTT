// Declares the monochrome app shell, action contracts, and shared UI helpers.
package app.zenptt

import app.zenptt.headset.*

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private enum class AppPage { Main, Settings }

data class AppUpdateActions(
    val check: (String) -> Unit = {},
    val downloadAndInstall: (String) -> Unit = {},
)

data class ChannelActions(
    val submitFrequency: (String) -> Boolean = { true },
    val frequencyDraftChanged: (String) -> Unit = {},
    val toggleConnection: () -> Unit = {},
    val pttDown: () -> Unit = {},
    val pttUp: () -> Unit = {},
    val toggleAccessiblePtt: () -> Unit = {},
    val releaseAccessiblePtt: () -> Boolean = { false },
    val applySettings: (String, String) -> Boolean = { _, _ -> true },
    val serverDraftChanged: () -> Unit = {},
    val setHeadsetSettings: (HeadsetSettings) -> Unit = {},
    val beginPttSetup: () -> Unit = {},
    val cancelPttSetup: () -> Unit = {},
    val nextSetupStep: () -> Unit = {},
    val retryPttSetup: () -> Unit = {},
    val choosePttBehavior: (ButtonBehavior) -> Unit = {},
    val selectHeadsetDevice: (String?) -> Unit = {},
    val confirmHeadsetTest: (Boolean) -> Unit = {},
    val savePttSetup: () -> Unit = {},
    val consumeUiError: (Int) -> Unit = {},
    val consumePttNotice: (Int) -> Unit = {},
    val pingServer: (String) -> Unit = {},
    val shareDebugInfo: () -> Unit = {},
    val sendDiagnosticReport: () -> Unit = {},
    val copyDiagnosticCode: () -> Unit = {},
    val openBatteryOptimizationSettings: () -> Unit = {},
)

private val MonochromeColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
    error = Color.Black,
    onError = Color.White,
)

private val MonochromeSelection = TextSelectionColors(
    handleColor = Color.Black,
    backgroundColor = Color.Black.copy(alpha = 0.18f),
)

@Composable
fun ZenPttApp(
    state: ChannelUiState,
    actions: ChannelActions,
    hardwareStatus: String = "",
    showBatteryOptimizationWarning: Boolean = false,
    appUpdateState: AppUpdateUiState = AppUpdateUiState(),
    appUpdateActions: AppUpdateActions = AppUpdateActions(),
    pttSetup: HeadsetSetupState? = null,
) {
    var pageName by rememberSaveable { mutableStateOf(AppPage.Main.name) }
    var settingsServer by rememberSaveable { mutableStateOf(state.serverAddress) }
    var settingsPowerSave by rememberSaveable { mutableStateOf(state.powerSaveTimeoutMinutes) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.pttNotice?.id, state.uiError?.id) {
        val notice = state.pttNotice
        if (notice != null) {
            snackbar.showSnackbar(
                message = notice.message,
                duration = if (notice.kind == PttNoticeKind.Queued) {
                    SnackbarDuration.Indefinite
                } else {
                    SnackbarDuration.Long
                },
            )
            if (notice.kind == PttNoticeKind.Rejected) {
                actions.consumePttNotice(notice.id)
            }
        } else {
            val current = state.uiError ?: return@LaunchedEffect
            if (current.target == UiErrorTarget.General) {
                snackbar.showSnackbar(current.message)
                actions.consumeUiError(current.id)
            }
        }
    }

    MaterialTheme(colorScheme = MonochromeColorScheme) {
        CompositionLocalProvider(LocalTextSelectionColors provides MonochromeSelection) {
            Box(Modifier.fillMaxSize()) {
                when (AppPage.valueOf(pageName)) {
                    AppPage.Main -> MainScreen(
                        state = state,
                        actions = actions,
                        onOpenSettings = {
                            actions.releaseAccessiblePtt()
                            settingsServer = state.serverAddress
                            settingsPowerSave = state.powerSaveTimeoutMinutes
                            pageName = AppPage.Settings.name
                        },
                    )
                    AppPage.Settings -> SettingsScreen(
                        state = state,
                        hardwareStatus = hardwareStatus,
                        showBatteryOptimizationWarning = showBatteryOptimizationWarning,
                        appUpdateState = appUpdateState,
                        appUpdateActions = appUpdateActions,
                        actions = actions,
                        serverAddress = settingsServer,
                        onServerAddressChange = { settingsServer = it },
                        powerSaveTimeoutMinutes = settingsPowerSave,
                        onPowerSaveTimeoutChange = { settingsPowerSave = it },
                        onReturnHome = { pageName = AppPage.Main.name },
                        snackbar = snackbar,
                        pttSetup = pttSetup,
                    )
                }
                if (AppPage.valueOf(pageName) == AppPage.Main) {
                    SnackbarHost(
                        hostState = snackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
    }
}

@Composable
internal fun InlineError(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_ui_warning), null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(message, color = Color.Black)
    }
}

@Composable
internal fun UiIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    testTag: String,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(testTag)) {
        Icon(painterResource(icon), description, modifier = Modifier.size(30.dp), tint = Color.Black)
    }
}

@Composable
internal fun monochromeTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    cursorColor = Color.Black,
    focusedBorderColor = Color.Black,
    unfocusedBorderColor = Color.Black.copy(alpha = 0.65f),
    errorBorderColor = Color.Black,
    errorCursorColor = Color.Black,
    focusedLabelColor = Color.Black,
    errorLabelColor = Color.Black,
)
