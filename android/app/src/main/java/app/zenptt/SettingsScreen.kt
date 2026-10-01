// Owns settings UI, diagnostics actions, and update controls.
package app.zenptt

import app.zenptt.headset.*


import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    state: ChannelUiState,
    hardwareStatus: String,
    showBatteryOptimizationWarning: Boolean,
    appUpdateState: AppUpdateUiState,
    appUpdateActions: AppUpdateActions,
    actions: ChannelActions,
    serverAddress: String,
    onServerAddressChange: (String) -> Unit,
    powerSaveTimeoutMinutes: String,
    onPowerSaveTimeoutChange: (String) -> Unit,
    onReturnHome: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    pttSetup: HeadsetSetupState? = null,
) {
    val scope = rememberCoroutineScope()
    var undoServer by rememberSaveable { mutableStateOf<String?>(null) }
    var undoPower by rememberSaveable { mutableStateOf<String?>(null) }
    var undoPtt by rememberSaveable(stateSaver = listSaver<HeadsetSettings, String>(
        save = { listOf(HeadsetSettingsCodec.encode(it)) },
        restore = { HeadsetSettingsCodec.decode(it.single()) },
    )) { mutableStateOf(HeadsetSettings()) }
    val serverFocus = remember { FocusRequester() }
    val powerFocus = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val largeText = configuration.fontScale >= 1.3f
    val paired = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !largeText
    val serverError = state.uiError?.takeIf { it.target == UiErrorTarget.Server }?.message
    val powerError = state.uiError?.takeIf { it.target == UiErrorTarget.PowerSave }?.message
    var savedSetupEvent by remember { mutableStateOf(state.headsetSetupSaved) }
    LaunchedEffect(state.headsetSetupSaved) {
        if (state.headsetSetupSaved != savedSetupEvent) {
            savedSetupEvent = state.headsetSetupSaved
            snackbar.showSnackbar("Headset setup saved. Test it in ECHO with the screen on and locked.")
        }
    }

    LaunchedEffect(Unit) {
        if (!appUpdateState.checking && !appUpdateState.downloading) {
            appUpdateActions.check(serverAddress)
        }
    }
    LaunchedEffect(state.uiError?.id) {
        if (pttSetup != null) return@LaunchedEffect
        when {
            serverError != null -> serverFocus.requestFocus()
            powerError != null -> powerFocus.requestFocus()
        }
    }
    LaunchedEffect(undoServer, undoPower, undoPtt) {
        val previousServer = undoServer ?: return@LaunchedEffect
        val previousPower = undoPower ?: return@LaunchedEffect
        val previousPtt = undoPtt
        val result = snackbar.showSnackbar("Defaults restored", actionLabel = "Undo")
        if (result == SnackbarResult.ActionPerformed) {
            onServerAddressChange(previousServer)
            onPowerSaveTimeoutChange(previousPower)
            actions.setHeadsetSettings(previousPtt)
            state.uiError
                ?.takeIf {
                    it.target == UiErrorTarget.Server ||
                    it.target == UiErrorTarget.PowerSave
                }
                ?.let { actions.consumeUiError(it.id) }
            actions.serverDraftChanged()
        }
        undoServer = null
        undoPower = null
    }

    fun saveAndReturn() {
        if (actions.applySettings(serverAddress, powerSaveTimeoutMinutes)) {
            undoServer = null
            undoPower = null
            onReturnHome()
        }
    }

    BackHandler(onBack = { saveAndReturn() })

    if (pttSetup != null) {
        PttButtonSetupScreen(pttSetup, actions, actions.savePttSetup)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            UiIconButton(
                icon = R.drawable.ic_ui_reset,
                description = "Reset settings",
                onClick = {
                    undoServer = serverAddress
                    undoPower = powerSaveTimeoutMinutes
                    undoPtt = state.headsetSettings
                    onServerAddressChange(DEFAULT_SERVER_ADDRESS)
                    onPowerSaveTimeoutChange(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES.toString())
                    actions.setHeadsetSettings(HeadsetSettings())
                    state.uiError
                        ?.takeIf {
                            it.target == UiErrorTarget.Server ||
                                it.target == UiErrorTarget.PowerSave
                        }
                        ?.let { actions.consumeUiError(it.id) }
                    actions.serverDraftChanged()
                },
                testTag = "resetSettings",
            )
            UiIconButton(
                icon = R.drawable.ic_ui_home,
                description = "Save and return home",
                onClick = ::saveAndReturn,
                testTag = "returnHome",
            )
        }
        Spacer(Modifier.height(4.dp))
        ServerCard(
            state = state,
            value = serverAddress,
            errorMessage = serverError,
            focusRequester = serverFocus,
            onValueChange = {
                onServerAddressChange(it)
                actions.serverDraftChanged()
                state.uiError?.takeIf { error -> error.target == UiErrorTarget.Server }
                    ?.let { error -> actions.consumeUiError(error.id) }
            },
            onPing = { actions.pingServer(serverAddress) },
        )
        HeadsetSettingsCard(state, actions)
        PowerSaveCard(
            value = powerSaveTimeoutMinutes,
            errorMessage = powerError,
            focusRequester = powerFocus,
            stacked = largeText,
            onValueChange = {
                onPowerSaveTimeoutChange(it.filter(Char::isDigit).take(4))
                state.uiError?.takeIf { error -> error.target == UiErrorTarget.PowerSave }
                    ?.let { error -> actions.consumeUiError(error.id) }
            },
            onDone = ::saveAndReturn,
        )
        DiagnosticsCard(state, actions) {
            actions.copyDiagnosticCode()
            snackbar.showSnackbar("Code copied")
        }
        if (paired) {
            SettingsPair(
                first = {
                    BatteryOptimizationRow(showBatteryOptimizationWarning, actions)
                },
                second = { UpdateRow(appUpdateState, appUpdateActions, serverAddress) },
            )
        } else {
            BatteryOptimizationRow(showBatteryOptimizationWarning, actions)
            UpdateRow(appUpdateState, appUpdateActions, serverAddress)
        }
        Spacer(Modifier.height(8.dp))
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@Composable
private fun BatteryOptimizationRow(
    showWarning: Boolean,
    actions: ChannelActions,
) {
    SettingsActionRow(
        R.drawable.ic_ui_power,
        "Battery optimization",
        actions.openBatteryOptimizationSettings,
        "batteryOptimizationSettings",
        if (showWarning) "Action recommended" else "Android settings",
    )
}

@Composable
private fun SettingsPair(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(1f)) { second() }
    }
}

@Composable
private fun ServerCard(
    state: ChannelUiState,
    value: String,
    errorMessage: String?,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onPing: () -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_ui_server), null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                isError = errorMessage != null,
                trailingIcon = errorMessage?.let {
                    { Icon(painterResource(R.drawable.ic_ui_warning), null, modifier = Modifier.size(22.dp)) }
                },
                supportingText = when {
                    errorMessage != null -> { { InlineError(errorMessage) } }
                    state.serverCheckStatus != null -> { { Text(state.serverCheckStatus) } }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onPing() }),
                colors = monochromeTextFieldColors(),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { if (errorMessage != null) error(errorMessage) }
                    .testTag("serverAddress"),
            )
            IconButton(onClick = onPing, modifier = Modifier.size(48.dp).testTag("pingServer")) {
                Icon(painterResource(R.drawable.ic_ui_ping), "Ping server")
            }
        }
    }
}

@Composable
private fun PowerSaveCard(
    value: String,
    errorMessage: String?,
    focusRequester: FocusRequester,
    stacked: Boolean,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    SettingsCard {
        if (stacked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_ui_battery), null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Power save", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            PowerSaveField(value, errorMessage, focusRequester, onValueChange, onDone, Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_ui_battery), null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Power save", modifier = Modifier.weight(1f), fontSize = 17.sp)
                PowerSaveField(value, errorMessage, focusRequester, onValueChange, onDone, Modifier.width(128.dp))
            }
        }
    }
}

@Composable
private fun PowerSaveField(
    value: String,
    errorMessage: String?,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        suffix = { Text("min") },
        singleLine = true,
        isError = errorMessage != null,
        supportingText = errorMessage?.let { message -> { InlineError(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = monochromeTextFieldColors(),
        modifier = modifier
            .focusRequester(focusRequester)
            .semantics { if (errorMessage != null) error(errorMessage) }
            .testTag("powerSaveTimeout"),
    )
}

@Composable
private fun DiagnosticsCard(
    state: ChannelUiState,
    actions: ChannelActions,
    onCopyDiagnosticCode: suspend () -> Unit,
) {
    val code = state.diagnosticReportCode
    val title = when {
        state.diagnosticUploading -> "Sending diagnostics\u2026"
        code != null -> state.diagnosticUploadStatus ?: "Report sent"
        state.diagnosticUploadStatus == "Unable to send diagnostics" -> "Retry diagnostics"
        else -> "Send debug info"
    }
    val scope = rememberCoroutineScope()
    val largeText = LocalConfiguration.current.fontScale >= 1.3f
    val sendAction = if (!state.diagnosticUploading) {
        actions.sendDiagnosticReport
    } else {
        null
    }
    SettingsCard(Modifier.defaultMinSize(minHeight = 68.dp).testTag("diagnosticsCard")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .then(
                        if (sendAction != null) Modifier.clickable(onClick = sendAction)
                        else Modifier,
                    )
                    .padding(end = 6.dp)
                    .testTag("sendDiagnosticReport"),
            ) {
                val stackCopy = largeText || maxWidth < 160.dp
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.height(48.dp).testTag("diagnosticSendTitle"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_ui_diagnostics),
                            null,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    code?.let {
                        if (stackCopy) {
                            Text(
                                it,
                                modifier = Modifier.testTag("diagnosticReportCode"),
                                fontWeight = FontWeight.Medium,
                            )
                            DiagnosticCopyButton(
                                onClick = { scope.launch { onCopyDiagnosticCode() } },
                                modifier = Modifier.align(Alignment.End),
                            )
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    it,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("diagnosticReportCode"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium,
                                )
                                DiagnosticCopyButton(
                                    onClick = { scope.launch { onCopyDiagnosticCode() } },
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clickable(onClick = actions.shareDebugInfo)
                    .padding(start = 6.dp)
                    .testTag("shareDebugInfo"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_ui_share), null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Share debug info", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun DiagnosticCopyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).testTag("copyDiagnosticCode"),
    ) {
        Icon(painterResource(R.drawable.ic_ui_copy), "Copy report code")
    }
}

@Composable
private fun UpdateRow(
    state: AppUpdateUiState,
    actions: AppUpdateActions,
    serverAddress: String,
) {
    val release = state.available
    val title = when {
        state.checking -> "Checking for updates…"
        state.downloading -> "Downloading update…"
        release != null -> "Install " + release.versionName
        else -> "Check for updates"
    }
    SettingsActionRow(
        icon = R.drawable.ic_ui_update,
        title = title,
        detail = state.status,
        progress = state.progress.takeIf { state.downloading },
        onClick = when {
            state.checking || state.downloading -> null
            release != null -> { { actions.downloadAndInstall(serverAddress) } }
            else -> { { actions.check(serverAddress) } }
        },
        enabled = !state.checking && !state.downloading,
        testTag = if (release == null) "checkForUpdates" else "installUpdate",
    )
}

@Composable
private fun SettingsActionRow(
    @DrawableRes icon: Int,
    title: String,
    onClick: (() -> Unit)?,
    testTag: String,
    detail: String? = null,
    enabled: Boolean = true,
    progress: Float? = null,
) {
    val targetProgress = progress?.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress ?: 0f,
        animationSpec = tween(durationMillis = 200),
        label = "settingsActionProgress",
    )
    SettingsCard(
        modifier = Modifier
            .defaultMinSize(minHeight = 68.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .then(
                if (targetProgress != null) Modifier.progressSemantics(targetProgress)
                else Modifier,
            )
            .testTag(testTag),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (targetProgress != null) {
                        clipRect(right = size.width * animatedProgress) {
                            drawRect(Color.Black)
                        }
                    }
                },
        ) {
            SettingsActionRowContent(
                icon = icon,
                title = title,
                detail = detail,
                color = Color.Black,
            )
            if (targetProgress != null) {
                SettingsActionRowContent(
                    icon = icon,
                    title = title,
                    detail = detail,
                    color = Color.White,
                    modifier = Modifier
                        .matchParentSize()
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            clipRect(right = size.width * animatedProgress) {
                                this@drawWithContent.drawContent()
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRowContent(
    @DrawableRes icon: Int,
    title: String,
    detail: String?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            detail?.takeIf(String::isNotBlank)?.let {
                Text(it, color = color, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Black),
        color = Color.White,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}
