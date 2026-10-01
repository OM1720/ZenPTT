$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$drawableDirectory = Join-Path $repoRoot "android\app\src\main\res\drawable"
$expected = [ordered]@{
    "ic_ui_bluetooth_connected.xml" = "m7 7 10 10-5 5V2l5 5L7 17"
    "ic_ui_hourglass.xml" = "M5 22h14"
    "ic_ui_pointer_off.xml" = "M10 4.5V4a2 2 0 0 0-2.41-1.957"
    "ic_ui_pointer.xml" = "M22 14a8 8 0 0 1-8 8"
    "ic_ui_mouse_pointer_click.xml" = "M14 4.1 12 6"
    "ic_ui_list_checks.xml" = "M13 5h8"
    "ic_ui_circle_check.xml" = "M2.0,12.0a10.0,10.0 0,1 0,20.0,0a10.0,10.0 0,1 0,-20.0,0"
    "ic_ui_arrow_right.xml" = "M5 12h14"
    "ic_ui_check.xml" = "M20 6 9 17l-5-5"
    "ic_ui_x.xml" = "M18 6 6 18"
    "ic_ui_battery.xml" = "M10,14"
    "ic_ui_circle_alert.xml" = "M12,2"
    "ic_ui_circle_power.xml" = "M7.998,9.003"
    "ic_ui_circle_power_inverse.xml" = "M7.998,9.003"
    "ic_ui_copy.xml" = "M10,8"
    "ic_ui_diagnostics.xml" = "M14.536,21.686"
    "ic_ui_headset_ptt.xml" = "M3,11"
    "ic_ui_home.xml" = "M15,21"
    "ic_ui_link_off.xml" = "M18.84,12.25"
    "ic_ui_mic.xml" = "M12,19"
    "ic_ui_mic_off.xml" = "M16.95,16.95"
    "ic_ui_ping.xml" = "M4.9,16.1"
    "ic_ui_power.xml" = "M11,7"
    "ic_ui_reset.xml" = "M3,12"
    "ic_ui_server.xml" = "M4,2"
    "ic_ui_settings.xml" = "M9.671,4.136"
    "ic_ui_settings_2.xml" = "M14,17"
    "ic_ui_share.xml" = "M18,2"
    "ic_ui_update.xml" = "M12,15"
    "ic_ui_volume_2.xml" = "M11,4.702"
    "ic_ui_warning.xml" = "M21.73,18"
}

$actualNames = @(
    Get-ChildItem -LiteralPath $drawableDirectory -Filter "ic_ui_*.xml" -File |
        ForEach-Object Name |
        Sort-Object
)
$expectedNames = @($expected.Keys | Sort-Object)
$differences = @(Compare-Object $expectedNames $actualNames)
if ($differences.Count -ne 0) {
    throw "Lucide UI icon set differs from the approved mapping: $differences"
}

foreach ($entry in $expected.GetEnumerator()) {
    $path = Join-Path $drawableDirectory $entry.Key
    $content = Get-Content -LiteralPath $path -Raw
    if ($content -notmatch 'android:viewportWidth="24"' -or
        $content -notmatch 'android:viewportHeight="24"') {
        throw "$($entry.Key) must use the Lucide 24x24 viewport"
    }
    if ($content -match 'strokeWidth="1\.8"' -or
        $content -notmatch 'strokeWidth="2"') {
        throw "$($entry.Key) must use the Lucide 2dp stroke"
    }
    if (-not $content.Contains($entry.Value)) {
        throw "$($entry.Key) does not contain its approved Lucide geometry"
    }
}

$manifest = Get-Content -LiteralPath (Join-Path $repoRoot "android\app\src\main\AndroidManifest.xml") -Raw
if ($manifest -notmatch 'android:icon="@mipmap/ic_launcher"' -or
    $manifest -notmatch 'android:roundIcon="@mipmap/ic_launcher_round"') {
    throw "The application must use the Yin-Yang launcher icons"
}
if (Test-Path -LiteralPath (Join-Path $drawableDirectory "ic_ptt_active.xml")) {
    throw "The legacy microphone application icon must not remain packaged"
}

Write-Output "Lucide and Yin-Yang icon assets passed"
