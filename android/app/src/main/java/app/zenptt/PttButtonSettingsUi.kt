// Renders the headset switch and guided hardware PTT setup in Compose.
package app.zenptt

import app.zenptt.headset.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HeadsetSettingsCard(state: ChannelUiState, actions: ChannelActions) {
    val settings = state.headsetSettings
    SettingsCard {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("headsetToggle")
            .toggleable(settings.enabled, enabled = settings.valid && !state.headsetChangePending, role = Role.Switch) {
                actions.setHeadsetSettings(settings.copy(enabled = it))
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_ui_headset_ptt), null, Modifier.size(28.dp))
            Text("Headset on/off", Modifier.weight(1f), fontSize = 17.sp)
            Switch(settings.enabled, onCheckedChange = null, enabled = settings.valid && !state.headsetChangePending, colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color.Black,
                uncheckedThumbColor = Color.Black, uncheckedTrackColor = Color.White, uncheckedBorderColor = Color.Black,
            ))
        }
        TextButton(onClick = actions.beginPttSetup, modifier = Modifier.testTag("headsetSetup"),
            enabled = !state.headsetChangePending && !state.pendingPtt && state.status !in setOf(SessionStatus.Requesting, SessionStatus.Transmitting, SessionStatus.Releasing)) {
            Icon(painterResource(R.drawable.ic_ui_settings_2), null, Modifier.size(24.dp), tint = Color.Black)
            Text("Set up headset", Modifier.padding(start = 12.dp), color = Color.Black)
        }
        if (state.headsetChangePending) CommandLabel("Wait.", R.drawable.ic_ui_hourglass)
        if (!settings.valid) Text("Saved setup is invalid. Set up your headset again.")
    }
}

@Composable
private fun PttOption(
    title: String,
    selected: Boolean,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag(tag)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(title, modifier = Modifier.padding(8.dp))
    }
}

@Composable
internal fun PttButtonSetupScreen(state: HeadsetSetupState, actions: ChannelActions, onSave: () -> Unit) {
    BackHandler(onBack = actions.cancelPttSetup)
    DisposableEffect(Unit) { onDispose { actions.cancelPttSetup() } }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp).testTag("pttSetup"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Set up headset", Modifier.weight(1f), fontSize = 22.sp)
            TextButton(onClick = actions.cancelPttSetup, modifier = Modifier.testTag("cancelPttSetup")) { CommandLabel("Cancel", R.drawable.ic_ui_x) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val stage = when (state.step) {
            SetupStep.Prepare -> 1
            SetupStep.Devices -> 2
            SetupStep.OtherButtons -> 4
            SetupStep.Behavior -> 5
            SetupStep.Test -> 6
            else -> 3
        }
        Text("Step $stage of 6")
        state.source?.let { Text("Connection: ${it.shortName}", Modifier.testTag("pttSetupSource")) }
        val (instruction, icon) = state.command.presentation
        CommandLabel(instruction, icon, Modifier.testTag("pttSetupInstruction")
            .semantics { liveRegion = LiveRegionMode.Polite })
        state.error?.let { Text(it) }
        if (state.round > 0 && state.step in setOf(SetupStep.Hold, SetupStep.Release)) Text("Test ${state.round} of 5")
        if (state.hid) Text("This button may work only while the app is on screen.")
        if (state.error != null) {
            OutlinedButton(modifier = Modifier.testTag("retryPttSetup"), onClick = if (state.step == SetupStep.Test) onSave else actions.retryPttSetup) { CommandLabel("Retry", R.drawable.ic_ui_reset) }
        } else {
            when (state.step) {
                SetupStep.Prepare, SetupStep.Switching, SetupStep.OtherButtons -> OutlinedButton(
                    onClick = actions.nextSetupStep,
                    enabled = state.step != SetupStep.OtherButtons || state.canNext,
                ) { CommandLabel("Continue", R.drawable.ic_ui_arrow_right) }
                SetupStep.Devices -> {
                    state.devices.forEach { device ->
                        OutlinedButton(onClick = { actions.selectHeadsetDevice(device.id) }, modifier = Modifier.fillMaxWidth()) { Text(device.name) }
                    }
                }
                SetupStep.Behavior -> {
                    Column(Modifier.selectableGroup()) {
                        PttOption("Hold to talk", state.behavior == ButtonBehavior.Hold, "pttHold", state.holdAvailable) {
                            actions.choosePttBehavior(ButtonBehavior.Hold)
                        }
                        Text("Hold to transmit. Release to stop.")
                        if (!state.holdAvailable) Text("Hold detection is unavailable for this button.")
                        PttOption("Press to toggle", state.behavior == ButtonBehavior.Toggle, "pttToggle") {
                            actions.choosePttBehavior(ButtonBehavior.Toggle)
                        }
                        Text("Press once to transmit. Press again to stop.")
                    }
                    OutlinedButton(onClick = actions.nextSetupStep) { CommandLabel("Continue", R.drawable.ic_ui_arrow_right) }
                }
                SetupStep.Test -> {
                    Text("No audio will be transmitted.")
                    Text(if (state.testOn) "Transmission would be on" else "Transmission would be off",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Row(Modifier.fillMaxWidth().toggleable(state.confirmed, enabled = !state.saving, role = Role.Checkbox,
                        onValueChange = actions.confirmHeadsetTest), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.confirmed, onCheckedChange = null, enabled = !state.saving)
                        Text("The indicator matches my button presses.")
                    }
                    OutlinedButton(onClick = onSave, enabled = !state.saving && state.testComplete && !state.testOn && state.confirmed) { CommandLabel("Save", R.drawable.ic_ui_check) }
                }
                else -> Unit
            }
        }
        }
    }
}

@Composable
private fun CommandLabel(text: String, icon: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp))
        Text(text)
    }
}
