param(
    [Parameter(Mandatory)]
    [ValidateSet("staged", "worktree", "message", "push")]
    [string]$Mode,
    [Parameter(ValueFromRemainingArguments)]
    [string[]]$HookArguments
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $repoRoot ".venv/Scripts/python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    $python = Join-Path $repoRoot ".venv/bin/python"
}
if (-not (Test-Path -LiteralPath $python)) {
    $python = (Get-Command python, python3 -ErrorAction SilentlyContinue |
        Select-Object -First 1).Source
}
if (-not $python) { throw "Python 3.10 or newer is required for publication checks" }

Push-Location $repoRoot
try {
    & $python (Join-Path $PSScriptRoot "check-publication.py") $Mode @HookArguments
    $result = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $result
