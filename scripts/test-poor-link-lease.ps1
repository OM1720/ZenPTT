param(
    [Parameter(Mandatory = $true)][string]$PackageDirectory,
    [ValidateSet(2, 3, 4)][int]$LeaseSeconds = 2,
    [string]$Modes = "blackout-1.25,blackout-1.5,blackout-2,blackout-3",
    [ValidateRange(1, 3)][int]$Repeats = 3,
    [string]$ServerUrl,
    [string]$SshConfigPath = "private/poor-link-ssh.json",
    [string]$OutputDirectory = "acceptance/artifacts/poor-link"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$runId = "lease-" + [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
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
    $arguments = @("scripts/poor-link/lease.py", "--lease-seconds", $LeaseSeconds,
        "--package-dir", $PackageDirectory, "--modes", $Modes,
        "--repeats", $Repeats, "--ssh-config", $SshConfigPath,
        "--output-dir", $OutputDirectory, "--run-id", $runId)
    if ($ServerUrl) { $arguments += @("--server-url", $ServerUrl) }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Lease run has measurement-system errors; inspect its results" }
} finally {
    & $python scripts/poor-link/cleanup.py (Join-Path $OutputDirectory $runId)
    if ($LASTEXITCODE -ne 0) { Write-Warning "Lease container cleanup could not finish" }
    Pop-Location
}
