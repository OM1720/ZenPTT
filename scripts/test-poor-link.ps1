param(
    [string]$Profiles = "baseline,sender-24,sender-32,sender-48,listener-24,listener-32,listener-48,unstable,sender-blackout-1,sender-blackout-2,sender-blackout-3,listener-blackout-3",
    [ValidateRange(1, 3)][int]$Repeats = 3,
    [string]$ServerUrl,
    [string]$SshConfigPath = "private/poor-link-ssh.json",
    [string]$OutputDirectory = "acceptance/artifacts/poor-link"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runId = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
$python = Join-Path $repoRoot ".venv/Scripts/python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $python = Join-Path $repoRoot ".venv/bin/python"
}
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project virtual environment is missing"
}
if (-not (Test-Path -LiteralPath $SshConfigPath -PathType Leaf)) {
    throw "Create the ignored local SSH config at $SshConfigPath (see docs/TESTING.md)"
}

Push-Location $repoRoot
try {
    $arguments = @("scripts/poor-link/run.py", "--profiles", $Profiles, "--repeats", $Repeats,
        "--ssh-config", $SshConfigPath, "--output-dir", $OutputDirectory, "--run-id", $runId)
    if ($ServerUrl) { $arguments += @("--server-url", $ServerUrl) }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Poor-link run has measurement-system errors; inspect its report" }
} finally {
    & $python scripts/poor-link/cleanup.py (Join-Path $OutputDirectory $runId)
    if ($LASTEXITCODE -ne 0) { Write-Warning "Poor-link container cleanup could not finish" }
    Pop-Location
}
