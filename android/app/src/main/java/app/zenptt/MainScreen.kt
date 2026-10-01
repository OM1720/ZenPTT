// Owns the main channel screen and Concentric Halo PTT control.
package app.zenptt


import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HaloMode {
    Offline,
    Transition,
    Ready,
    Transmitting,
    Receiving,
    ConnectionError,
    PttUnavailable,
}

enum class HaloCenterIcon(@DrawableRes val drawableRes: Int) {
    Microphone(R.drawable.ic_ui_mic),
    Volume(R.drawable.ic_ui_volume_2),
    ConnectionAlert(R.drawable.ic_ui_circle_alert),
    MicrophoneOff(R.drawable.ic_ui_mic_off),
}

data class HaloVisualSpec(
    val mode: HaloMode,
    val ringVisible: Boolean,
    val gapFraction: Float,
    val rotating: Boolean,
    val fillCenter: Boolean,
    val centerIcon: HaloCenterIcon,
)

internal const val HALO_SEGMENT_COUNT = 16
internal const val HALO_TRANSITION_GOOD_GAP_FRACTION = 0.10f
internal const val HALO_FAIR_GAP_FRACTION = 0.25f
internal const val HALO_POOR_GAP_FRACTION = 0.50f
internal const val CONNECTION_ICON_BLINK_MS = 600

enum class ConnectionIconMode { Off, Connected, Connecting }

@Composable
fun MainScreen(
    state: ChannelUiState,
    actions: ChannelActions,
    onOpenSettings: () -> Unit = {},
) {
    var frequency by rememberSaveable(state.channelCode) { mutableStateOf(state.channelCode) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val frequencyError = state.uiError
        ?.takeIf { it.target == UiErrorTarget.Frequency }
        ?.message ?: localError
    val frequencyFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(state.uiError?.id, localError) {
        if (frequencyError != null) frequencyFocus.requestFocus()
    }

    fun submitFrequency(candidate: String = frequency): Boolean {
        val accepted = actions.submitFrequency(candidate)
        if (!accepted) {
            localError = if (candidate.isBlank()) "Enter a frequency" else CHANNEL_CODE_ERROR
            return false
        }
        localError = null
        frequency = InputValidator.channelCode(candidate).orEmpty()
        keyboard?.hide()
        focusManager.clearFocus()
        return true
    }

    val frequencyField: @Composable () -> Unit = {
        FrequencyField(
            value = frequency,
            choices = state.frequencyChoices,
            participantCount = participantCountText(state, frequency),
            errorMessage = frequencyError,
            focusRequester = frequencyFocus,
            onValueChange = {
                frequency = it.uppercase().take(InputValidator.MAX_CHANNEL_CODE_LENGTH)
                actions.frequencyDraftChanged(frequency)
                localError = null
                state.uiError?.takeIf { error -> error.target == UiErrorTarget.Frequency }
                    ?.let { error -> actions.consumeUiError(error.id) }
            },
            onDone = { submitFrequency() },
            onSelect = { selected ->
                frequency = selected
                localError = null
                submitFrequency(selected)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MainTopBar(state, actions.toggleConnection, onOpenSettings)
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Box(Modifier.weight(0.9f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    frequencyField()
                }
                HaloBox(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                )
            }
        } else {
            Spacer(Modifier.height(28.dp))
            frequencyField()
            HaloBox(
                state = state,
                actions = actions,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MainTopBar(
    state: ChannelUiState,
    onToggleConnection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val disconnected = state.currentChannel == null
    val connectionError = state.status == SessionStatus.ConnectionError
    val description = when {
        connectionError -> "Reconnect"
        disconnected -> "Connect"
        else -> "Disconnect"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionIconButton(state, description, onToggleConnection)
        Text(
            text = "ZenPTT",
            color = Color.Black,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
        )
        UiIconButton(R.drawable.ic_ui_settings, "Settings", onOpenSettings, "openSettings")
    }
}

internal fun connectionIconMode(state: ChannelUiState): ConnectionIconMode = when {
    state.currentChannel == null || state.status == SessionStatus.ConnectionError -> ConnectionIconMode.Off
    state.status in setOf(SessionStatus.Connecting, SessionStatus.Reconnecting) ->
        ConnectionIconMode.Connecting
    else -> ConnectionIconMode.Connected
}

@Composable
private fun ConnectionIconButton(
    state: ChannelUiState,
    description: String,
    onClick: () -> Unit,
) {
    val iconMode = connectionIconMode(state)
    val iconAlpha = when (iconMode) {
        ConnectionIconMode.Off, ConnectionIconMode.Connected -> 1f
        ConnectionIconMode.Connecting -> {
            val transition = rememberInfiniteTransition(label = "connectionIcon")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = CONNECTION_ICON_BLINK_MS * 2
                        1f at 0
                        1f at CONNECTION_ICON_BLINK_MS - 1
                        0f at CONNECTION_ICON_BLINK_MS
                        0f at CONNECTION_ICON_BLINK_MS * 2 - 1
                    },
                ),
                label = "connectionIconAlpha",
            )
            alpha
        }
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .testTag("connectionToggle"),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer { alpha = iconAlpha }
                .testTag("connectionIcon"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(
                    if (iconMode == ConnectionIconMode.Connected) {
                        R.drawable.ic_ui_circle_power_inverse
                    } else {
                        R.drawable.ic_ui_circle_power
                    },
                ),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(30.dp)
                    .graphicsLayer {
                        rotationZ = if (iconMode == ConnectionIconMode.Connected) 0f else 180f
                    },
            )
        }
    }
}

internal fun participantCountText(state: ChannelUiState, frequencyDraft: String): String {
    val connectedChannel = state.currentChannel ?: return "—"
    if (frequencyDraft != connectedChannel) return "—"
    if (state.status in setOf(
            SessionStatus.Connecting,
            SessionStatus.Reconnecting,
            SessionStatus.ConnectionError,
        )
    ) return "—"
    return state.participantCount?.toString() ?: "—"
}

@Composable
private fun FrequencyField(
    value: String,
    choices: List<String>,
    participantCount: String,
    errorMessage: String?,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onDone: () -> Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fieldShape = MaterialTheme.shapes.extraSmall
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) expanded = true
    }
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 564.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.weight(1f).widthIn(max = 520.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("ROOM1", color = Color.Black.copy(alpha = 0.35f)) },
                singleLine = true,
                isError = errorMessage != null,
                trailingIcon = errorMessage?.let {
                    {
                        Icon(
                            painterResource(R.drawable.ic_ui_warning),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
                supportingText = errorMessage?.let { message -> { InlineError(message) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { expanded = !onDone() }),
                colors = monochromeTextFieldColors(),
                shape = fieldShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { fieldWidthPx = it.size.width }
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) expanded = true }
                    .semantics {
                        contentDescription = "Frequency"
                        if (errorMessage != null) error(errorMessage)
                    }
                    .testTag("frequency"),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = fieldShape,
                containerColor = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, Color.Black),
                modifier = Modifier
                    .width(with(density) { fieldWidthPx.toDp() })
                    .testTag("frequencyChoices"),
            ) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                choice,
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(choice)
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("frequencyChoice:$choice"),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(56.dp)
                .semantics {
                    contentDescription = if (participantCount == "—") {
                        "Active connections unavailable"
                    } else {
                        "$participantCount active connections"
                    }
                }
                .testTag("participantCount"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                participantCount,
                color = Color.Black,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun HaloBox(
    state: ChannelUiState,
    actions: ChannelActions,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val haloSize = minOf(maxWidth, maxHeight).coerceAtMost(380.dp)
        ConcentricHaloPtt(
            state = state,
            onPttDown = actions.pttDown,
            onPttUp = actions.pttUp,
            onAccessibilityToggle = actions.toggleAccessiblePtt,
            modifier = Modifier.size(haloSize),
        )
    }
}

@Composable
fun ConcentricHaloPtt(
    state: ChannelUiState,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    modifier: Modifier = Modifier,
    onAccessibilityToggle: () -> Unit = {},
) {
    val canHold = !state.pendingPtt && state.status !in setOf(
        SessionStatus.Requesting,
        SessionStatus.Transmitting,
        SessionStatus.Busy,
    )
    val accessibleAction = accessiblePttAction(state)
    val currentCanHold by rememberUpdatedState(canHold)
    val currentPttDown by rememberUpdatedState(onPttDown)
    val currentPttUp by rememberUpdatedState(onPttUp)
    val spec = haloVisualSpec(state)
    val statusLabel = when {
        state.status == SessionStatus.ConnectionError -> state.status.label
        state.currentChannel == null -> "Offline"
        else -> state.status.label
    }
    val accessibility = buildString {
        append(statusLabel + ", quality " + state.audioPathQuality.name.lowercase())
        append(", RTT " + (state.linkMetrics.rttMs?.let { it.toString() + " milliseconds" } ?: "unknown"))
        append(", PTT " + (state.linkMetrics.pttGrantMs?.let { it.toString() + " milliseconds" } ?: "unknown"))
        append(", gaps " + (state.linkMetrics.recentSequenceGaps?.toString() ?: "unknown"))
        state.pttNotice?.let { append(", " + it.message) }
    }
    val actionLabel = when (accessibleAction) {
        AccessiblePttAction.Start -> "Start transmission"
        AccessiblePttAction.Stop -> "Finish transmission"
        AccessiblePttAction.Disabled -> null
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics {
                this[SemanticsProperties.TestTag] = "ptt"
                contentDescription = "PTT"
                stateDescription = accessibility
                role = Role.Button
                if (actionLabel == null) {
                    disabled()
                } else {
                    onClick(label = actionLabel) {
                        onAccessibilityToggle()
                        true
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (currentCanHold) {
                        currentPttDown()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            currentPttUp()
                        }
                    }
                }
            },
    ) {
        HaloCanvas(spec)
        Icon(
            painter = painterResource(spec.centerIcon.drawableRes),
            contentDescription = null,
            tint = if (spec.fillCenter) Color.White else Color.Black,
            modifier = Modifier.size(76.dp),
        )
    }
}

@Composable
private fun HaloCanvas(spec: HaloVisualSpec) {
    if (spec.rotating) {
        val transition = rememberInfiniteTransition(label = "connectingHalo")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 6_000, easing = LinearEasing),
            ),
            label = "connectingHaloRotation",
        )
        Canvas(Modifier.fillMaxSize()) { drawHalo(spec, rotation) }
    } else {
        Canvas(Modifier.fillMaxSize()) { drawHalo(spec) }
    }
}

internal fun haloVisualSpec(state: ChannelUiState): HaloVisualSpec {
    val mode = when {
        state.status == SessionStatus.ConnectionError -> HaloMode.ConnectionError
        state.currentChannel == null -> HaloMode.Offline
        state.pttError != null || state.status == SessionStatus.Busy -> HaloMode.PttUnavailable
        state.status in setOf(
            SessionStatus.Connecting,
            SessionStatus.Reconnecting,
            SessionStatus.Requesting,
            SessionStatus.Releasing,
        ) -> HaloMode.Transition
        state.status == SessionStatus.Transmitting -> HaloMode.Transmitting
        state.status == SessionStatus.Receiving ||
            state.status == SessionStatus.PlayingEcho -> HaloMode.Receiving
        else -> HaloMode.Ready
    }
    val rotating = mode == HaloMode.Transition
    val gapFraction = when (state.audioPathQuality) {
        AudioPathQuality.Good -> if (rotating) HALO_TRANSITION_GOOD_GAP_FRACTION else 0f
        AudioPathQuality.Fair -> HALO_FAIR_GAP_FRACTION
        AudioPathQuality.Poor -> HALO_POOR_GAP_FRACTION
    }
    return HaloVisualSpec(
        mode = mode,
        ringVisible = mode !in setOf(HaloMode.Offline, HaloMode.ConnectionError),
        gapFraction = gapFraction,
        rotating = rotating,
        fillCenter = mode == HaloMode.Transmitting,
        centerIcon = when (mode) {
            HaloMode.Receiving -> HaloCenterIcon.Volume
            HaloMode.Transition -> if (
                state.status == SessionStatus.Reconnecting && state.playbackActive
            ) {
                HaloCenterIcon.Volume
            } else {
                HaloCenterIcon.Microphone
            }
            HaloMode.ConnectionError -> HaloCenterIcon.ConnectionAlert
            HaloMode.PttUnavailable -> HaloCenterIcon.MicrophoneOff
            else -> HaloCenterIcon.Microphone
        },
    )
}

private fun DrawScope.drawHalo(spec: HaloVisualSpec, rotationDegrees: Float = 0f) {
    val outerRadius = size.minDimension / 2f - 4.dp.toPx()
    val centerRadius = (outerRadius - 24.dp.toPx()).coerceAtLeast(8.dp.toPx())
    if (spec.fillCenter) {
        drawCircle(Color.Black, centerRadius, center)
    }
    if (!spec.ringVisible) return

    val strokeWidth = 2.dp.toPx()
    if (spec.gapFraction == 0f) {
        drawCircle(Color.Black, outerRadius, center, style = Stroke(strokeWidth))
        return
    }
    val sectorSweep = 360f / HALO_SEGMENT_COUNT
    val visibleBlackSweep = sectorSweep * (1f - spec.gapFraction)
    val capSweep = Math.toDegrees((strokeWidth / outerRadius).toDouble()).toFloat()
    val pathSweep = (visibleBlackSweep - capSweep).coerceAtLeast(0.1f)
    rotate(rotationDegrees, center) {
        repeat(HALO_SEGMENT_COUNT) { index ->
            val segmentCenter = -90f + index * sectorSweep
            drawArc(
                color = Color.Black,
                startAngle = segmentCenter - pathSweep / 2f,
                sweepAngle = pathSweep,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - outerRadius, center.y - outerRadius),
                size = androidx.compose.ui.geometry.Size(outerRadius * 2f, outerRadius * 2f),
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}
