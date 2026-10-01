param(
    [string]$ServerUrl = "http://127.0.0.1:8080",
    [int]$IdleSeconds = 45
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $repoRoot ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Project virtual environment is missing" }
$diagnosticsLimitLine = Get-Content -LiteralPath (Join-Path $repoRoot "server.local.env") |
    Where-Object { $_ -match '^DIAGNOSTICS_MAX_BODY_BYTES=' } |
    Select-Object -First 1
if (-not $diagnosticsLimitLine) { throw "DIAGNOSTICS_MAX_BODY_BYTES is missing" }
$diagnosticsMaxBodyBytes = [int]$diagnosticsLimitLine.Substring(
    $diagnosticsLimitLine.IndexOf('=') + 1
)

if (-not $env:ANDROID_HOME) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path -LiteralPath $defaultSdk -PathType Container) {
        $env:ANDROID_HOME = $defaultSdk
    }
}
if (-not $env:ANDROID_HOME) { throw "ANDROID_HOME is required for APK signature verification" }

$downloadedApk = Join-Path ([System.IO.Path]::GetTempPath()) (
    "zenptt-live-gate-" + [guid]::NewGuid() + ".apk"
)

try {
    $health = Invoke-RestMethod -Uri "$ServerUrl/health"
    if ($health.status -ne "ok") { throw "Server health check failed" }
    & $python (Join-Path $PSScriptRoot "test-idle-websocket.py") $ServerUrl $IdleSeconds
    if ($LASTEXITCODE -ne 0) { throw "Idle WebSocket check failed" }

    & $python (Join-Path $PSScriptRoot "test-websocket-message-limit.py") $ServerUrl
    if ($LASTEXITCODE -ne 0) { throw "WebSocket message limit check failed" }

    & $python (Join-Path $PSScriptRoot "test-diagnostics-limit.py") $ServerUrl $diagnosticsMaxBodyBytes
    if ($LASTEXITCODE -ne 0) { throw "Caddy diagnostics body limit check failed" }


    $diagnostic = [ordered]@{
        schemaVersion = 1
        createdAtMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        appVersion = "live-gate"
        androidVersion = "automated"
        networkStatus = "Connected"
        headsetStatus = "Not checked"
        audioRoute = "Not checked"
        details = "channel=none`nlive_gate=true"
    } | ConvertTo-Json
    $receipt = Invoke-RestMethod `
        -Method Post `
        -Uri "$ServerUrl/diagnostics" `
        -ContentType "application/json" `
        -Body $diagnostic
    if ($receipt.report_id -notmatch '^\d{6}-[0-9A-HJKMNP-TV-Z]{4}$') {
        throw "Server returned an invalid diagnostic code"
    }

    $storedDiagnostic = & docker compose exec -T zenptt-server `
        cat "/data/diagnostics/$($receipt.report_id).json"
    if ($LASTEXITCODE -ne 0) { throw "Stored diagnostic report was not found" }
    $storedDiagnostic = $storedDiagnostic | ConvertFrom-Json
    if (
        $storedDiagnostic.report_id -ne $receipt.report_id -or
        $storedDiagnostic.report.appVersion -ne "live-gate" -or
        $storedDiagnostic.report.details -ne "channel=none`nlive_gate=true"
    ) {
        throw "Stored diagnostic report verification failed"
    }

    $release = Invoke-RestMethod -Uri "$ServerUrl/app/latest"
    if ($release.version_code -lt 1 -or $release.size_bytes -lt 1) {
        throw "Server returned invalid APK metadata"
    }
    Invoke-WebRequest -Uri "$ServerUrl/app/download" -OutFile $downloadedApk
    $apk = Get-Item -LiteralPath $downloadedApk
    $sha256 = (Get-FileHash -LiteralPath $downloadedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($apk.Length -ne $release.size_bytes) { throw "Downloaded APK size mismatch" }
    if ($sha256 -ne $release.sha256) { throw "Downloaded APK hash mismatch" }

    $buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME "build-tools") -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1
    $apksigner = Join-Path $buildTools.FullName "apksigner.bat"
    & $apksigner verify --verbose $downloadedApk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed" }

    Write-Output "Live diagnostics: $($receipt.report_id)"
    Write-Output "Live APK: $($release.version_name) ($($release.version_code))"
    Write-Output "SHA-256: $sha256"
} finally {
    Remove-Item -LiteralPath $downloadedApk -Force -ErrorAction SilentlyContinue
}
