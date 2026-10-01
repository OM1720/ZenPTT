param([switch]$KeepRunning)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose = @("compose", "-p", "zenptt-web-gate", "--env-file", "server.local.env", "-f", "compose.yaml", "-f", "web/compose.test.yaml")
$previousUrl = $env:ZENPTT_WEB_URL
$previousHosting = $env:ZENPTT_WEB_HOSTING
$previousProject = $env:ZENPTT_WEB_TEST_PROJECT
Push-Location $repoRoot
try {
    & (Join-Path $PSScriptRoot "build-web.ps1")
    & docker @compose up -d --build --force-recreate
    if ($LASTEXITCODE -ne 0) { throw "Web gate stack startup failed" }
    $ready = $false
    foreach ($attempt in 1..30) {
        try {
            $health = Invoke-RestMethod http://127.0.0.1:18081/health -TimeoutSec 2
            if ($health.status -eq "ok") { $ready = $true; break }
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw "Web gate stack is not healthy" }
    $env:ZENPTT_WEB_URL = "http://127.0.0.1:18081"
    $env:ZENPTT_WEB_HOSTING = "1"
    $env:ZENPTT_WEB_TEST_PROJECT = "zenptt-web-gate"
    Push-Location web
    try {
        & npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw "Locked browser test dependencies could not be installed" }
        & npm run test:browser
        if ($LASTEXITCODE -ne 0) { throw "Production browser scenarios failed" }
        & npm run test:audio
        if ($LASTEXITCODE -ne 0) { throw "Real Chrome AudioWorklet/WASM signal checks failed" }
        & npm run test:restart
        if ($LASTEXITCODE -ne 0) { throw "Production web restart recovery failed" }
    } finally { Pop-Location }
    Write-Output "Production web gate passed at $env:ZENPTT_WEB_URL/web/"
} finally {
    $env:ZENPTT_WEB_URL = $previousUrl
    $env:ZENPTT_WEB_HOSTING = $previousHosting
    $env:ZENPTT_WEB_TEST_PROJECT = $previousProject
    if (-not $KeepRunning) { & docker @compose down }
    Pop-Location
}
