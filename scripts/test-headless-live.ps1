param([string[]]$Scenarios = @(
    "before_grant", "uplink:20", "burst_end", "terminal", "downlink:0", "contract", "echo"
), [switch]$CheckMetadataOnly)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$project = "zenptt-headless-live-$PID"
$compose = @(
    "compose", "-p", $project, "--env-file", "server.local.env",
    "-f", "compose.yaml", "-f", "examples/qrz_bot/compose.yaml", "-f", "headless/compose.test.yaml",
    "--profile", "qrz-bot"
)

function Assert-ReceivedMetadata {
    param([object[]]$Sources, [object[]]$Observations, [string]$Scenario)
    if ($Scenario -notin @("downlink:0", "contract")) { throw "Unknown metadata scenario" }
    if ($Sources.Count -ne 2 -or $Observations.Count -ne 2 -or
        $Sources[0].burst_id -eq $Sources[1].burst_id) {
        throw "Expected exactly two distinct sources and two handler invocations"
    }
    for ($index = 0; $index -lt 2; $index++) {
        $source = $Sources[$index]
        $observed = $Observations[$index]
        foreach ($field in @("burst_id", "burst_index", "first_sequence", "frames",
                "losses", "decode_errors", "reason", "session_epoch", "pcm_rms", "tone_fraction")) {
            if ($field -notin $observed.PSObject.Properties.Name -or $null -eq $observed.$field) {
                throw "Missing metadata field: $field"
            }
        }
        foreach ($field in @("burst_index", "first_sequence", "frames", "session_epoch")) {
            if (($observed.$field -isnot [int] -and $observed.$field -isnot [long]) -or
                $observed.$field -lt 0) { throw "Invalid metadata integer: $field" }
        }
        if ($observed.burst_id -ne $source.burst_id -or
            $observed.burst_index -ne $source.burst_index -or
            $observed.first_sequence + $observed.frames -ne $source.final_next_sequence -or
            $observed.reason -ne $source.reason -or $source.reason -ne "complete" -or
            $observed.losses -isnot [array] -or $observed.losses.Count -ne 0 -or
            $observed.decode_errors -isnot [array] -or $observed.decode_errors.Count -ne 0) {
            throw "Metadata differs from the independent source: $($source.burst_id)"
        }
        if (($Scenario -eq "contract" -or $index -eq 1) -and $observed.first_sequence -ne 0) {
            throw "A complete source acquired a partial receive prefix"
        }
        if ($Scenario -eq "contract" -or $index -eq 0) {
            if (-not ($observed.pcm_rms -ge 1000 -and $observed.tone_fraction -ge 0.8)) {
                throw "Incoming PCM did not preserve the independent 700 Hz source tone"
            }
        }
    }
    if ($Scenario -eq "contract") {
        if ($Observations[1].session_epoch -le $Observations[0].session_epoch) {
            throw "Server restart did not advance the logical session epoch"
        }
    } elseif ($Observations[0].first_sequence -le 0 -or
        $Observations[1].session_epoch -ne $Observations[0].session_epoch) {
        throw "History reset did not preserve the session and deliver a partial tail"
    }
}

function Test-ReceivedMetadataChecks {
    $rejected = 0
    foreach ($scenario in @("downlink:0", "contract")) {
        $sources = @(
            [PSCustomObject]@{burst_id="first"; burst_index=7; final_next_sequence=1000; reason="complete"},
            [PSCustomObject]@{burst_id="second"; burst_index=9; final_next_sequence=1; reason="complete"}
        )
        $observations = @(
            [PSCustomObject]@{burst_id="first"; burst_index=7; first_sequence=800; frames=200;
                losses=@(); decode_errors=@(); reason="complete"; session_epoch=4; pcm_rms=5000; tone_fraction=0.95},
            [PSCustomObject]@{burst_id="second"; burst_index=9; first_sequence=0; frames=1;
                losses=@(); decode_errors=@(); reason="complete"; session_epoch=4; pcm_rms=3000; tone_fraction=0.6}
        )
        if ($scenario -eq "contract") {
            $sources[0].final_next_sequence = 32
            $sources[1].final_next_sequence = 64
            $sources[1].burst_index = 0
            $observations[0].first_sequence = 0
            $observations[0].frames = 32
            $observations[1].frames = 64
            $observations[1].burst_index = 0
            $observations[1].session_epoch = 5
            $observations[1].tone_fraction = 0.95
        }
        Assert-ReceivedMetadata -Sources $sources -Observations $observations -Scenario $scenario
        for ($index = 0; $index -lt 2; $index++) {
            $badValues = @{
                burst_id="wrong"; burst_index=($observations[$index].burst_index + 1);
                first_sequence=1; frames=99; losses=@([PSCustomObject]@{first_sequence=0; count=1});
                decode_errors=@(0); reason="expired"; session_epoch=$(if ($index -eq 0) {100} else {0})
            }
            foreach ($field in $badValues.Keys) {
                foreach ($omit in @($false, $true)) {
                    $corrupt = @($observations | ConvertTo-Json -Depth 6 | ConvertFrom-Json)
                    if ($omit) { $corrupt[$index].PSObject.Properties.Remove($field) }
                    else { $corrupt[$index].$field = $badValues[$field] }
                    $failed = $false
                    try { Assert-ReceivedMetadata -Sources $sources -Observations $corrupt -Scenario $scenario }
                    catch { $failed = $true }
                    if (-not $failed) { throw "Metadata oracle accepted $scenario/$index/$field/omit=$omit" }
                    $rejected++
                }
            }
        }
        foreach ($corrupt in @(@($observations[1], $observations[0]), @($observations[0], $observations[0]))) {
            $failed = $false
            try { Assert-ReceivedMetadata -Sources $sources -Observations $corrupt -Scenario $scenario }
            catch { $failed = $true }
            if (-not $failed) { throw "Metadata oracle accepted reordered or duplicate delivery" }
            $rejected++
        }
    }
    Write-Output "Metadata validation passed: two positive cases and $rejected rejected corruptions"
}

if ($CheckMetadataOnly) {
    Test-ReceivedMetadataChecks
    return
}

$python = Join-Path $repoRoot ".venv/Scripts/python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $python = Join-Path $repoRoot ".venv/bin/python"
}
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project virtual environment is missing: $python"
}

function Invoke-BoundedDocker {
    param(
        [string[]]$Arguments,
        [DateTime]$Deadline,
        [string]$Fault = "",
        [string]$Handler = "qrz"
    )
    $timeoutSeconds = [Math]::Ceiling(($Deadline - [DateTime]::UtcNow).TotalSeconds)
    if ($timeoutSeconds -le 0) {
        throw "Docker command started after its deadline: $($Arguments -join ' ')"
    }
    $previousFault = $env:ZENPTT_TEST_FAULT
    $previousHandler = $env:ZENPTT_TEST_HANDLER
    try {
        if ($Fault) { $env:ZENPTT_TEST_FAULT = $Fault }
        $env:ZENPTT_TEST_HANDLER = $Handler
        $output = @(& $python (Join-Path $PSScriptRoot "run-bounded.py") $timeoutSeconds docker @Arguments 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Bounded Docker command failed with code ${LASTEXITCODE}: $($Arguments -join ' ')`n$($output -join "`n")"
        }
        if ([DateTime]::UtcNow -gt $Deadline) {
            throw "Docker command completed after its deadline: $($Arguments -join ' ')"
        }
        return $output
    } finally {
        $env:ZENPTT_TEST_FAULT = $previousFault
        $env:ZENPTT_TEST_HANDLER = $previousHandler
    }
}

Push-Location $repoRoot
try {
    $buildDeadline = [DateTime]::UtcNow.AddSeconds(600)
    Invoke-BoundedDocker -Arguments ($compose + @("build")) -Deadline $buildDeadline |
        Write-Output

    $runDeadline = [DateTime]::UtcNow.AddSeconds(600)
    $firstScenario = $true
    foreach ($scenario in $Scenarios) {
        if ($scenario -eq "echo") {
            $scenarioDeadline = [DateTime]::UtcNow.AddSeconds(300)
            Invoke-BoundedDocker -Arguments ($compose + @(
                "rm", "-s", "-f", "qrz-bot", "echo-supervisor", "fault-proxy"
            )) -Deadline $scenarioDeadline | Write-Output
            Invoke-BoundedDocker -Arguments ($compose + @(
                "up", "-d", "--no-build", "--wait", "--wait-timeout", "60",
                "zenptt-server", "caddy", "fault-proxy", "echo-supervisor"
            )) -Deadline $scenarioDeadline -Fault "none" | Write-Output
            $echoOutput = @(Invoke-BoundedDocker -Arguments ($compose + @(
                "run", "--rm", "--no-deps",
                "-v", "${repoRoot}/scripts/test-headless-live.py:/test.py:ro",
                "qrz-bot", "python", "/test.py", "--echo"
            )) -Deadline $scenarioDeadline -Fault "none")
            $echoOutput | Write-Output
            if (($echoOutput -join "`n") -notmatch "Echo live isolation passed: clients=3 responses=6 long_seconds=55") {
                throw "Echo isolation scenario failed"
            }
            Invoke-BoundedDocker -Arguments ($compose + @(
                "rm", "-s", "-f", "echo-supervisor", "fault-proxy"
            )) -Deadline $scenarioDeadline | Write-Output
            Invoke-BoundedDocker -Arguments ($compose + @(
                "up", "-d", "--no-build", "--wait", "--wait-timeout", "60",
                "fault-proxy", "echo-supervisor"
            )) -Deadline $scenarioDeadline -Fault "echo_bot" | Write-Output
            $restartClient = "$project-echo-restart-client"
            Invoke-BoundedDocker -Arguments ($compose + @(
                "run", "-d", "--no-deps", "--name", $restartClient,
                "-v", "${repoRoot}/scripts/test-headless-live.py:/test.py:ro",
                "qrz-bot", "python", "/test.py", "--echo-restarts"
            )) -Deadline $scenarioDeadline | Write-Output
            $supervisorRestarted = $false
            $serverRestarted = $false
            do {
                $restartOutput = @(Invoke-BoundedDocker -Arguments @("logs", $restartClient) `
                    -Deadline $scenarioDeadline)
                $restartText = $restartOutput -join "`n"
                if ($restartText -match "Traceback") { throw $restartText }
                if (-not $supervisorRestarted -and $restartText -match "echo_supervisor_restart_ready") {
                    Invoke-BoundedDocker -Arguments ($compose + @("restart", "echo-supervisor")) `
                        -Deadline $scenarioDeadline -Fault "echo_bot" | Write-Output
                    $supervisorRestarted = $true
                }
                if (-not $serverRestarted -and $restartText -match "echo_server_restart_ready") {
                    Invoke-BoundedDocker -Arguments ($compose + @("restart", "zenptt-server")) `
                        -Deadline $scenarioDeadline -Fault "echo_bot" | Write-Output
                    $serverRestarted = $true
                }
                if ([DateTime]::UtcNow -ge $scenarioDeadline) {
                    throw "Echo restart scenario timed out: $restartText"
                }
            } until ($restartText -match "Echo restart recovery passed")
            $restartExitCode = Invoke-BoundedDocker -Arguments @("wait", $restartClient) `
                -Deadline $scenarioDeadline
            if ($restartExitCode -ne "0" -or -not $supervisorRestarted -or -not $serverRestarted -or
                $restartText -notmatch "echo_bot_fault_recovered") {
                throw "Echo restart client failed: $restartText"
            }
            $restartOutput | Write-Output
            $echoProxyLogs = @(Invoke-BoundedDocker -Arguments ($compose + @(
                "logs", "--no-color", "fault-proxy"
            )) -Deadline $scenarioDeadline -Fault "echo_bot")
            if (($echoProxyLogs -join "`n") -notmatch "fault_proxy_triggered scenario=echo_bot") {
                throw "Ticket-authenticated Echo bot fault did not trigger"
            }
            Write-Output "Headless live scenario passed: echo"
            continue
        }
        $handler = if ($scenario -in @("downlink:0", "contract")) {
            "metadata"
        } else {
            "qrz"
        }
        $botService = "qrz-bot"
        if ([DateTime]::UtcNow -ge $runDeadline) {
            throw "Headless integration scenarios exceeded 600 seconds"
        }
        $scenarioDeadline = [DateTime]::UtcNow.AddSeconds(60)
        if ($runDeadline -lt $scenarioDeadline) { $scenarioDeadline = $runDeadline }
        if (-not $firstScenario) {
            Invoke-BoundedDocker -Arguments ($compose + @(
                "rm", "-s", "-f", "qrz-bot", "fault-proxy"
            )) -Deadline $scenarioDeadline -Fault $scenario -Handler $handler | Write-Output
        }
        $firstScenario = $false
        $services = @("zenptt-server", "caddy")
        $services += "fault-proxy"
        if ($scenario -ne "contract") { $services += $botService }
        Invoke-BoundedDocker -Arguments ($compose + @(
            "up", "-d", "--no-build", "--wait", "--wait-timeout", "60"
        ) + $services) -Deadline $scenarioDeadline -Fault $scenario -Handler $handler | Write-Output
        $clientArguments = $compose + @(
            "run", "--rm", "--no-deps",
            "-v", "${repoRoot}/scripts/test-headless-live.py:/test.py:ro",
            "qrz-bot", "python", "/test.py"
        )
        if ($scenario -eq "contract") {
            $clientName = "$project-contract-client"
            Invoke-BoundedDocker -Arguments ($compose + @(
                "run", "-d", "--no-deps", "--name", $clientName,
                "-v", "${repoRoot}/scripts/test-headless-live.py:/test.py:ro",
                "-v", "${repoRoot}/scripts/test-headless-contract.py:/contract.py:ro",
                "qrz-bot", "python", "/contract.py"
            )) -Deadline $scenarioDeadline -Fault $scenario -Handler $handler | Write-Output
            $botStarted = $false
            $serverRestarted = $false
            do {
                $clientOutput = @(Invoke-BoundedDocker -Arguments @("logs", $clientName) `
                    -Deadline $scenarioDeadline)
                $clientText = $clientOutput -join "`n"
                if ($clientText -match "Traceback") { throw $clientText }
                if (-not $botStarted -and $clientText -match "contract_late_join_ready") {
                    Invoke-BoundedDocker -Arguments ($compose + @("up", "-d", "--no-build", "qrz-bot")) `
                        -Deadline $scenarioDeadline -Fault $scenario -Handler $handler | Write-Output
                    $botStarted = $true
                }
                if (-not $serverRestarted -and $clientText -match "contract_restart_ready") {
                    Invoke-BoundedDocker -Arguments ($compose + @("restart", "zenptt-server")) `
                        -Deadline $scenarioDeadline -Fault $scenario -Handler $handler | Write-Output
                    $serverRestarted = $true
                }
                if ([DateTime]::UtcNow -ge $scenarioDeadline) { throw "Contract scenario timed out: $clientText" }
            } until ($clientText -match "headless_live_result")
            $exitCode = Invoke-BoundedDocker -Arguments @("wait", $clientName) -Deadline $scenarioDeadline
            if ($exitCode -ne "0" -or -not $serverRestarted) { throw "Contract client failed: $clientText" }
        } else {
            if ($handler -eq "metadata") { $clientArguments += "--long-source" }
            $clientOutput = @(Invoke-BoundedDocker -Arguments $clientArguments `
                -Deadline $scenarioDeadline -Fault $scenario -Handler $handler)
        }
        $clientOutput | Write-Output
        $clientResults = @(
            foreach ($line in $clientOutput) {
                if ($line -match 'headless_live_result (\{.*\})') {
                    $Matches[1] | ConvertFrom-Json
                }
            }
        )
        if ($clientResults.Count -ne 1) {
            throw "Expected one independent-client result, got $($clientResults.Count)"
        }
        $sources = @($clientResults[0].sources)
        $sourceIds = @($sources | ForEach-Object { $_.burst_id })
        if ($sourceIds.Count -ne 2 -or $sourceIds[0] -eq $sourceIds[1]) {
            throw "Independent client did not publish two distinct source burst IDs"
        }

        $proxyLogs = Invoke-BoundedDocker -Arguments ($compose + @(
            "logs", "--no-color", "fault-proxy"
        )) -Deadline $scenarioDeadline -Fault $scenario -Handler $handler
        if ($scenario -ne "contract" -and ($proxyLogs -join "`n") -notmatch "fault_proxy_triggered scenario=$([regex]::Escape($scenario))") {
            throw "Fault condition did not trigger for scenario $scenario"
        }
        $botLogs = Invoke-BoundedDocker -Arguments ($compose + @(
            "logs", "--no-color", $botService
        )) -Deadline $scenarioDeadline -Fault $scenario -Handler $handler
        $responseResults = @(
            foreach ($line in $botLogs) {
                if ($line -match 'response_finished source=([^ ]+) status=([^ ]+) reason=([^ ]+)') {
                    [PSCustomObject]@{
                        source = $Matches[1]
                        status = $Matches[2]
                        reason = $Matches[3]
                    }
                }
            }
        )
        if ($responseResults.Count -ne 2) {
            throw "Expected exactly two runner results, got $($responseResults.Count)"
        }
        foreach ($sourceId in $sourceIds) {
            $matching = @($responseResults | Where-Object { $_.source -eq $sourceId })
            if (
                $matching.Count -ne 1 -or
                $matching[0].status -ne "sent" -or
                $matching[0].reason -ne "complete"
            ) {
                throw "Source burst $sourceId did not produce exactly one sent/complete result"
            }
        }
        if ($scenario -eq "terminal" -and ($proxyLogs -join "`n") -notmatch `
            "fault_proxy_upstream_end_forwarded scenario=terminal") {
            throw "The terminal scenario did not forward burst_end upstream"
        }
        if ($handler -eq "metadata") {
            $observations = @(
                foreach ($line in $botLogs) {
                    if ($line -match 'tail_observer_result (\{.*\})') {
                        $Matches[1] | ConvertFrom-Json
                    }
                }
            )
            Assert-ReceivedMetadata -Sources $sources -Observations $observations -Scenario $scenario
        }
        if ([DateTime]::UtcNow -gt $scenarioDeadline) {
            throw "Headless integration scenario exceeded 60 seconds: $scenario"
        }
        Write-Output "Headless live scenario passed: $scenario"
    }
} finally {
    $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(60)
    Invoke-BoundedDocker -Arguments ($compose + @(
        "down", "--volumes", "--remove-orphans"
    )) -Deadline $cleanupDeadline | Write-Output
    Pop-Location
}

Write-Output "Headless live integration passed"
