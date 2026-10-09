param([switch]$KeepRunning)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose = @("compose", "-p", "zenptt-web-gate", "--env-file", "server.local.env", "-f", "compose.yaml", "-f", "web/compose.test.yaml")
$previousUrl = $env:ZENPTT_WEB_URL
$previousHosting = $env:ZENPTT_WEB_HOSTING
$previousProject = $env:ZENPTT_WEB_TEST_PROJECT
$runId = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ") + "-" + [guid]::NewGuid().ToString("N")
$output = Join-Path $repoRoot "acceptance/artifacts/web-gate/$runId"
New-Item -ItemType Directory -Path $output | Out-Null
$python = Join-Path $repoRoot ".venv/Scripts/python.exe"
if (-not (Test-Path -LiteralPath $python)) { $python = Join-Path $repoRoot ".venv/bin/python" }
$result = [ordered]@{
    started_at = [DateTime]::UtcNow.ToString("o")
    stages = @(); primary_error = $null; collection_errors = @(); cleanup_errors = @()
    cleanup_status = "pending"; keep_running = [bool]$KeepRunning
}
$stackStarted = $false
$failure = $null

function Invoke-BoundedDocker([string[]]$Arguments, [string]$Name) {
    $dockerPath = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    New-Item -ItemType File -Path (Join-Path $output "$Name.stdout.log") -Force | Out-Null
    & $python (Join-Path $PSScriptRoot "run-bounded.py") 45 $dockerPath @Arguments `
        2> (Join-Path $output "$Name.stderr.log") |
        Tee-Object -FilePath (Join-Path $output "$Name.stdout.log")
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

Push-Location $repoRoot
try {
    if (-not (Test-Path -LiteralPath $python)) { throw "Project virtual environment is missing" }
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw "Docker Engine is required for the web gate" }
    $occupied = @(& docker @compose ps --all --quiet)
    if ($LASTEXITCODE -ne 0) { throw "Unable to check web gate stack ownership" }
    if ($occupied.Count -ne 0) { throw "The local web test stack is already in use" }
    & (Join-Path $PSScriptRoot "build-web.ps1")
    $stackStarted = $true
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
        foreach ($stage in @("browser", "audio", "restart")) {
            $stageOutput = Join-Path $output $stage
            $npmPath = (Get-Command npm -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
            New-Item -ItemType File -Path (Join-Path $output "$stage.stdout.log") -Force | Out-Null
            & $python (Join-Path $PSScriptRoot "run-bounded.py") 900 $npmPath run "test:$stage" -- --output $stageOutput `
                2> (Join-Path $output "$stage.stderr.log") |
                Tee-Object -FilePath (Join-Path $output "$stage.stdout.log")
            $exitCode = $LASTEXITCODE
            $result.stages += @{ name = $stage; exit_code = $exitCode; output = $stageOutput }
            if ($exitCode -ne 0) { throw "Production $stage scenarios failed ($exitCode)" }
        }
    } finally { Pop-Location }
} catch {
    $failure = $_
    $result.primary_error = $_.Exception.Message
} finally {
    try {
        if ($stackStarted) {
            foreach ($collection in @(
                @{ name = "compose-logs"; arguments = @("logs", "--no-color", "--timestamps") },
                @{ name = "compose-state"; arguments = @("ps", "--all", "--format", "json") }
            )) {
                try { Invoke-BoundedDocker ($compose + $collection.arguments) $collection.name | Out-Null }
                catch { $result.collection_errors += $_.Exception.Message }
            }
        }
        $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output "result.json")
        if ($stackStarted -and -not $KeepRunning) {
            try { Invoke-BoundedDocker ($compose + @("down")) "compose-down" | Out-Null }
            catch { $result.cleanup_errors += $_.Exception.Message }
        }
        $result.cleanup_status = if ($result.cleanup_errors.Count) { "failed" } else { "complete" }
        $result.finished_at = [DateTime]::UtcNow.ToString("o")
        $result.status = if ($failure -or $result.collection_errors.Count -or $result.cleanup_errors.Count) { "failed" } else { "passed" }
        $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output "result.json")
    } finally {
        $env:ZENPTT_WEB_URL = $previousUrl
        $env:ZENPTT_WEB_HOSTING = $previousHosting
        $env:ZENPTT_WEB_TEST_PROJECT = $previousProject
        Pop-Location
        Write-Output "Web gate evidence: $output"
    }
}
if ($failure) { throw $failure }
if ($result.status -ne "passed") { throw "Web gate evidence collection or cleanup failed; see $output" }
Write-Output "Production web gate passed"
