param([string]$ApkDirectory)

$ErrorActionPreference = "Stop"
$publish = Join-Path $PSScriptRoot "publish-apk.ps1"
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "zenptt-publisher-" + [guid]::NewGuid()
)

try {
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    & $publish -SkipBuild -ReleaseDirectory $tempDirectory -ApkDirectory $ApkDirectory

    $release = Get-Content -LiteralPath (Join-Path $tempDirectory "release.json") -Raw |
        ConvertFrom-Json
    $publishedApk = Join-Path $tempDirectory ("zenptt-{0}.apk" -f $release.version_code)
    $fixedTimestamp = [datetime]::SpecifyKind([datetime]"2000-01-01T00:00:00", [DateTimeKind]::Utc)
    (Get-Item -LiteralPath $publishedApk).LastWriteTimeUtc = $fixedTimestamp

    & $publish -SkipBuild -ReleaseDirectory $tempDirectory -ApkDirectory $ApkDirectory
    if ((Get-Item -LiteralPath $publishedApk).LastWriteTimeUtc -ne $fixedTimestamp) {
        throw "Identical versioned APK was overwritten instead of reused"
    }

    [System.IO.File]::WriteAllText($publishedApk, "conflicting APK bytes")
    $rejected = $false
    try {
        & $publish -SkipBuild -ReleaseDirectory $tempDirectory -ApkDirectory $ApkDirectory
    } catch {
        $rejected = $_.Exception.Message -match "Increment versionCode"
    }
    if (-not $rejected) { throw "Conflicting APK with the same versionCode was not rejected" }
    if ([System.IO.File]::ReadAllText($publishedApk) -ne "conflicting APK bytes") {
        throw "Conflicting publication overwrote the existing versioned APK"
    }

    Write-Output "Immutable APK publisher gate passed"
} finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
