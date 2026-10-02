param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$publicRoot = "232b520916009327249dfbfda1f6e3a75cb0a04d"
$version = "8.30.1"
$archiveName = "gitleaks_${version}_windows_x64.zip"
$archiveHash = "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e"

Push-Location $repoRoot
try {
    $roots = @(& git rev-list --max-parents=0 HEAD)
    if ($LASTEXITCODE -ne 0 -or $roots.Count -ne 1 -or $roots[0] -ne $publicRoot) {
        throw "Use the public ZenPTT checkout; do not attach the legacy history to GitHub"
    }
    $existingHooks = & git config --local --get core.hooksPath
    if ($existingHooks -and $existingHooks -ne ".githooks") {
        throw "Custom hooks are already configured. Integrate them before enabling publication hooks."
    }
    $toolDirectory = Join-Path $repoRoot "private/tools/gitleaks/$version"
    $scanner = Join-Path $toolDirectory "gitleaks.exe"
    if (-not (Test-Path -LiteralPath $scanner)) {
        if (-not $IsWindows -or $env:PROCESSOR_ARCHITECTURE -ne "AMD64") {
            throw "This installer supports Windows x64. See docs/DEVELOPMENT_WORKFLOW.md for other systems."
        }
        New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
        & gh release download "v$version" --repo gitleaks/gitleaks `
            --pattern $archiveName --dir $toolDirectory --clobber
        if ($LASTEXITCODE -ne 0) { throw "Unable to download the pinned Gitleaks release" }
        $archive = Join-Path $toolDirectory $archiveName
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archiveHash) {
            throw "Gitleaks archive checksum mismatch"
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolDirectory -Force
        Remove-Item -LiteralPath $archive
    }
    $installedVersion = & $scanner version
    if ($LASTEXITCODE -ne 0 -or $installedVersion -ne $version) {
        throw "Unexpected Gitleaks version"
    }
    & git config --local zenptt.publicRoot $publicRoot
    if ($LASTEXITCODE -ne 0) { throw "Unable to record the public Git root" }
    & git config --local zenptt.publicRemote "https://github.com/OM1720/ZenPTT.git"
    if ($LASTEXITCODE -ne 0) { throw "Unable to record the public Git remote" }
    & git config --local core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) { throw "Unable to enable publication hooks" }
    Write-Output "Publication hooks enabled. Local commits and pushes still require separate user commands."
} finally {
    Pop-Location
}
