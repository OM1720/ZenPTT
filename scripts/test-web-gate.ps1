param([string]$GateScript = (Join-Path $PSScriptRoot "test-web.ps1"))

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path $repoRoot ("acceptance/artifacts/web-gate-fixtures/" + [guid]::NewGuid().ToString("N"))
$originalPath = $env:PATH
$originalFixture = $env:ZENPTT_GATE_FIXTURE
$originalMode = $env:ZENPTT_GATE_FIXTURE_MODE
$originalUrl = $env:ZENPTT_WEB_URL
$originalHosting = $env:ZENPTT_WEB_HOSTING
$originalProject = $env:ZENPTT_WEB_TEST_PROJECT
$originalLocation = (Get-Location).Path
$pwsh = (Get-Command pwsh -CommandType Application).Source
$passed = $false
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    foreach ($mode in @("success", "audio-failure", "occupied", "keep", "collection-failure", "cleanup-failure", "both-failures", "startup-failure")) {
        $fixture = Join-Path $fixtureRoot $mode
        foreach ($directory in @("scripts", "web", "shims", ".venv/Scripts")) {
            New-Item -ItemType Directory -Path (Join-Path $fixture $directory) | Out-Null
        }
        Copy-Item -LiteralPath $GateScript -Destination (Join-Path $fixture "scripts/test-web.ps1")
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "run-bounded.py") -Destination (Join-Path $fixture "scripts/run-bounded.py")
        Copy-Item -LiteralPath (Join-Path $repoRoot ".venv/Scripts/python.exe") -Destination (Join-Path $fixture ".venv/Scripts/python.exe")
        Copy-Item -LiteralPath (Join-Path $repoRoot ".venv/pyvenv.cfg") -Destination (Join-Path $fixture ".venv/pyvenv.cfg")
        Set-Content -LiteralPath (Join-Path $fixture "scripts/build-web.ps1") -Value 'Write-Output "Fixture build"'
        @'
Add-Content -LiteralPath (Join-Path $env:ZENPTT_GATE_FIXTURE "calls.log") -Value ("docker " + ($args -join " "))
if ($args -contains "info") { Write-Output "fixture-engine" }
if ($args -contains "--quiet" -and $env:ZENPTT_GATE_FIXTURE_MODE -eq "occupied") { Write-Output "existing-container" }
if ($args -contains "logs") {
    Write-Output "fixture-server-journal"
    if ($env:ZENPTT_GATE_FIXTURE_MODE -eq "collection-failure") { exit 7 }
}
if ($args -contains "up" -and $env:ZENPTT_GATE_FIXTURE_MODE -eq "startup-failure") { exit 10 }
if ($args -contains "down" -and $env:ZENPTT_GATE_FIXTURE_MODE -in @("cleanup-failure", "both-failures")) { exit 9 }
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixture "shims/docker.ps1")
        @'
if ($args[0] -eq "run") {
    $stage = $args[1] -replace '^test:', ''
    $index = [array]::IndexOf($args, "--output")
    if ($index -lt 0) { throw "Missing isolated output directory" }
    $output = $args[$index + 1]
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $output "marker.txt") -Value $stage
    if ($stage -eq "audio") {
        New-Item -ItemType Directory -Path (Join-Path $output "audio-diagnostics") | Out-Null
        Set-Content -LiteralPath (Join-Path $output "audio-diagnostics/page.json") -Value '{"fixture":true}'
    }
    Write-Output "Fixture $stage stdout"
    [Console]::Error.WriteLine("Fixture $stage stderr")
    if ($stage -eq "audio" -and $env:ZENPTT_GATE_FIXTURE_MODE -in @("audio-failure", "both-failures")) { exit 3 }
}
exit 0
'@ | Set-Content -LiteralPath (Join-Path $fixture "shims/npm.ps1")
        foreach ($name in @("docker", "npm")) {
            $shim = Join-Path $fixture "shims/$name.ps1"
            Set-Content -LiteralPath (Join-Path $fixture "shims/$name.cmd") -Value "@echo off`r`n`"$pwsh`" -NoProfile -File `"$shim`" %*"
        }
        $env:PATH = (Join-Path $fixture "shims") + [IO.Path]::PathSeparator + $originalPath
        $env:ZENPTT_GATE_FIXTURE = $fixture
        $env:ZENPTT_GATE_FIXTURE_MODE = $mode
        $env:ZENPTT_WEB_URL = "fixture-url"
        $env:ZENPTT_WEB_HOSTING = "fixture-hosting"
        $env:ZENPTT_WEB_TEST_PROJECT = "fixture-project"
        function global:Invoke-RestMethod { return @{ status = "ok" } }
        $caught = $null
        try { & (Join-Path $fixture "scripts/test-web.ps1") -KeepRunning:($mode -eq "keep") }
        catch { $caught = $_.Exception.Message }
        finally { Remove-Item Function:\Invoke-RestMethod }
        if ($env:ZENPTT_WEB_URL -ne "fixture-url" -or $env:ZENPTT_WEB_HOSTING -ne "fixture-hosting" -or $env:ZENPTT_WEB_TEST_PROJECT -ne "fixture-project" -or (Get-Location).Path -ne $originalLocation) {
            throw "Gate did not restore its environment/location: $mode"
        }
        $records = @(Get-ChildItem -LiteralPath (Join-Path $fixture "acceptance/artifacts/web-gate") -Filter result.json -Recurse)
        if ($records.Count -ne 1) { throw "Missing unique gate result: $mode" }
        $record = Get-Content -LiteralPath $records[0].FullName -Raw | ConvertFrom-Json
        $output = $records[0].DirectoryName
        $calls = Get-Content -LiteralPath (Join-Path $fixture "calls.log")
        $expectedSuccess = $mode -in @("success", "keep")
        if (($record.status -eq "passed") -ne $expectedSuccess -or ([bool]$caught -eq $expectedSuccess)) { throw "Wrong gate outcome: $mode" }
        if ($mode -eq "occupied") {
            if ($calls -match ' up | down') { throw "Occupied stack was mutated" }
        } else {
            $logsIndex = [array]::FindIndex([string[]]$calls, [Predicate[string]]{ param($line) $line -match ' logs ' })
            $downIndex = [array]::FindIndex([string[]]$calls, [Predicate[string]]{ param($line) $line -match ' down$' })
            if ($logsIndex -lt 0 -or ($mode -ne "keep" -and $downIndex -le $logsIndex)) { throw "Journals were not collected before cleanup: $mode" }
            if ($mode -eq "keep" -and $downIndex -ge 0) { throw "KeepRunning stopped the stack" }
        }
        if ($expectedSuccess) {
            foreach ($stage in @("browser", "audio", "restart")) {
                if (-not (Test-Path -LiteralPath (Join-Path $output "$stage/marker.txt"))) { throw "Lost $stage artifacts" }
                foreach ($stream in @("stdout", "stderr")) {
                    if (-not (Select-String -LiteralPath (Join-Path $output "$stage.$stream.log") -Pattern "Fixture $stage $stream" -Quiet)) { throw "Missing $stage $stream" }
                }
            }
            if (-not (Test-Path -LiteralPath (Join-Path $output "audio/audio-diagnostics/page.json"))) { throw "Restart erased audio diagnostics" }
        }
        if ($mode -in @("audio-failure", "both-failures") -and $record.primary_error -notmatch 'audio.*3') { throw "Primary audio failure was lost" }
        if ($mode -in @("cleanup-failure", "both-failures") -and ($record.cleanup_status -ne "failed" -or -not $record.cleanup_errors.Count)) { throw "Cleanup failure was lost" }
        if ($mode -eq "collection-failure" -and -not $record.collection_errors.Count) { throw "Collection failure was lost" }
        Write-Output "Web gate regression passed: $mode"
    }
    $passed = $true
} finally {
    $env:PATH = $originalPath
    $env:ZENPTT_GATE_FIXTURE = $originalFixture
    $env:ZENPTT_GATE_FIXTURE_MODE = $originalMode
    $env:ZENPTT_WEB_URL = $originalUrl
    $env:ZENPTT_WEB_HOSTING = $originalHosting
    $env:ZENPTT_WEB_TEST_PROJECT = $originalProject
    if ($passed) {
        $resolved = [IO.Path]::GetFullPath($fixtureRoot)
        $allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot "acceptance/artifacts/web-gate-fixtures")) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw "Unexpected fixture cleanup path" }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    } else { Write-Output "Failed gate fixture retained: $fixtureRoot" }
}
