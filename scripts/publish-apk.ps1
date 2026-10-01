param(
    [switch]$SkipBuild,
    [string]$ReleaseDirectory,
    [string]$ApkDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot "android"
$gradle = Join-Path $androidRoot "gradlew.bat"
$signingProperties = Join-Path $androidRoot "signing.properties"
if (-not (Test-Path -LiteralPath $signingProperties -PathType Leaf)) {
    throw "Copy android/signing.properties.example to android/signing.properties and configure your signing key before publishing"
}

if (-not $env:ANDROID_HOME) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path -LiteralPath $defaultSdk -PathType Container) {
        $env:ANDROID_HOME = $defaultSdk
    }
}
if (-not $env:ANDROID_HOME) { throw "ANDROID_HOME is required for the APK build" }
if ($ApkDirectory -and -not $SkipBuild) {
    throw "ApkDirectory requires SkipBuild and an existing verified build"
}

if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & $gradle --no-daemon --console=plain :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Android APK build failed" }
    } finally {
        Pop-Location
    }
}

$outputDirectory = if ($ApkDirectory) { $ApkDirectory } else {
    Join-Path $androidRoot "app\build\outputs\apk\debug"
}
$metadataPath = Join-Path $outputDirectory "output-metadata.json"
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$elements = @($metadata.elements)
if ($elements.Count -ne 1) { throw "Expected exactly one debug APK" }

$element = $elements[0]
$sourceApk = Join-Path $outputDirectory $element.outputFile
if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) { throw "APK not found: $sourceApk" }

$releaseDirectory = if ($ReleaseDirectory) { $ReleaseDirectory } else {
    Join-Path $repoRoot "server\releases"
}
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
$publishedApk = Join-Path $releaseDirectory ("zenptt-{0}.apk" -f $element.versionCode)
$source = Get-Item -LiteralPath $sourceApk
$sourceHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash.ToLowerInvariant()
if (Test-Path -LiteralPath $publishedApk -PathType Leaf) {
    $existing = Get-Item -LiteralPath $publishedApk
    $existingHash = (Get-FileHash -LiteralPath $publishedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($existing.Length -ne $source.Length -or $existingHash -ne $sourceHash) {
        throw "versionCode $($element.versionCode) is already published with different APK bytes. Increment versionCode."
    }
    Write-Output "Reusing identical ZenPTT APK for versionCode $($element.versionCode)"
} else {
    $temporaryApk = Join-Path $releaseDirectory (".zenptt-{0}.apk.tmp" -f $element.versionCode)
    try {
        Copy-Item -LiteralPath $sourceApk -Destination $temporaryApk -Force
        Move-Item -LiteralPath $temporaryApk -Destination $publishedApk
    } finally {
        if (Test-Path -LiteralPath $temporaryApk) {
            Remove-Item -LiteralPath $temporaryApk -Force
        }
    }
}

$apk = Get-Item -LiteralPath $publishedApk
$release = [ordered]@{
    version_code = [int]$element.versionCode
    version_name = [string]$element.versionName
    sha256 = $sourceHash
    size_bytes = [long]$apk.Length
}
$releaseJson = $release | ConvertTo-Json
$metadataTemporary = Join-Path $releaseDirectory ".release.json.tmp"
$metadataPublished = Join-Path $releaseDirectory "release.json"
[System.IO.File]::WriteAllText(
    $metadataTemporary,
    $releaseJson + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
Move-Item -LiteralPath $metadataTemporary -Destination $metadataPublished -Force

Write-Output "Published ZenPTT $($release.version_name) ($($release.version_code))"
Write-Output "SHA-256: $($release.sha256)"
Write-Output "Local endpoint: http://<server>:8080/app/latest"
