// Maps hardware setup intent to app-owned labels and icons.
package app.zenptt

import app.zenptt.headset.SetupCommand
import app.zenptt.headset.HeadsetSource

internal val HeadsetSource.shortName: String get() = when (this) {
    HeadsetSource.Spp -> "SPP"
    HeadsetSource.Ble -> "BLE"
    HeadsetSource.Media -> "Media"
    HeadsetSource.Hid -> "HID"
}

// Text and artwork are application resources; the hardware module exposes intent only.
internal val SetupCommand.presentation: Pair<String, Int> get() = when (this) {
    SetupCommand.Connect -> "Connect your headset." to R.drawable.ic_ui_bluetooth_connected
    SetupCommand.Select -> "Select your headset." to R.drawable.ic_ui_headset_ptt
    SetupCommand.Wait -> "Wait." to R.drawable.ic_ui_hourglass
    SetupCommand.KeepReleased -> "Keep PTT released." to R.drawable.ic_ui_pointer_off
    SetupCommand.Hold -> "Press and hold PTT." to R.drawable.ic_ui_pointer
    SetupCommand.Release -> "Release PTT." to R.drawable.ic_ui_pointer_off
    SetupCommand.Press -> "Press PTT." to R.drawable.ic_ui_mouse_pointer_click
    SetupCommand.PressAgain -> "Press PTT again." to R.drawable.ic_ui_mouse_pointer_click
    SetupCommand.OtherButtons -> "Press other buttons." to R.drawable.ic_ui_mouse_pointer_click
    SetupCommand.ChooseMode -> "Choose a PTT mode." to R.drawable.ic_ui_list_checks
    SetupCommand.CheckIndicator -> "Check the indicator." to R.drawable.ic_ui_circle_check
    SetupCommand.Continue -> "Continue" to R.drawable.ic_ui_arrow_right
    SetupCommand.Retry -> "Retry" to R.drawable.ic_ui_reset
}
