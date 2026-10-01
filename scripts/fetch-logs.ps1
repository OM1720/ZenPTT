param(
    [string]$SshTarget,
    [string]$RemoteDirectory = "/opt/zenptt",
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($SshTarget)) {
    $firstLine = Get-Content -LiteralPath (Join-Path $repoRoot "server.env") -TotalCount 1
    if ($firstLine -notmatch '^ZENPTT_DOMAIN=([A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9])$') {
        throw "The first server.env line must contain ZENPTT_DOMAIN"
    }
    $domain = $Matches[1]
    if ($domain -eq "example.com" -or $domain.EndsWith(".example.com")) {
        throw "Replace the example DNS name in server.env or pass -SshTarget explicitly"
    }
    $SshTarget = "root@$domain"
}

if ($SshTarget -notmatch '^(?:[A-Za-z0-9][A-Za-z0-9._-]*@)?[A-Za-z0-9][A-Za-z0-9._-]*$') {
    throw "SshTarget must be an SSH alias, host, or user@host"
}
if ($RemoteDirectory -notmatch '^/[A-Za-z0-9._/-]+$' -or "/$RemoteDirectory/" -match '/\.\.?/') {
    throw "RemoteDirectory must be a safe absolute Linux path"
}

foreach ($command in @("ssh", "scp", "tar")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command is required and was not found in PATH"
    }
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "support\inbox"
}
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null

$timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ")
$destination = Join-Path $resolvedOutput $timestamp
$staging = Join-Path $resolvedOutput (".fetch-" + [guid]::NewGuid())
$temporaryArchive = Join-Path $resolvedOutput (".zenptt-support-" + [guid]::NewGuid() + ".tar.gz")
$remoteScript = "$RemoteDirectory/runtime/collect-logs.sh"
$remoteArchive = "$RemoteDirectory/support/zenptt-support-latest.tar.gz"
$remoteCommand = 'if [ "$(id -u)" -eq 0 ]; then bash ''{0}''; else sudo -n bash ''{0}''; fi' -f $remoteScript

try {
    & ssh $SshTarget $remoteCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Remote log collection failed. The SSH user must be root or have passwordless sudo."
    }

    & scp "${SshTarget}:$remoteArchive" $temporaryArchive
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to download the support bundle"
    }

    $entries = @(& tar -tzf $temporaryArchive)
    if ($LASTEXITCODE -ne 0 -or $entries.Count -eq 0) {
        throw "The downloaded support bundle is damaged or empty"
    }
    foreach ($entry in $entries) {
        $normalized = $entry -replace '^\./', ''
        if ($normalized.StartsWith('/') -or $normalized.Contains('\') -or "/$normalized/" -match '/\.\.?/') {
            throw "The support bundle contains an unsafe path: $entry"
        }
    }

    New-Item -ItemType Directory -Path $staging | Out-Null
    & tar -xzf $temporaryArchive -C $staging
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to extract the support bundle"
    }

    foreach ($requiredPath in @("summary.txt", "server.log", "caddy.log", "diagnostics")) {
        if (-not (Test-Path -LiteralPath (Join-Path $staging $requiredPath))) {
            throw "The support bundle is missing $requiredPath"
        }
    }

    Move-Item -LiteralPath $staging -Destination $destination
    Write-Output "ZenPTT logs downloaded: $destination"
} finally {
    if (Test-Path -LiteralPath $temporaryArchive) {
        Remove-Item -LiteralPath $temporaryArchive -Force
    }
    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}
