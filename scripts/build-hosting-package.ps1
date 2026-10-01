param(
    [switch]$SkipChecks,
    [switch]$ReusePublishedApk,
    [string]$AndroidWorkDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$serverEnv = Join-Path $repoRoot "server.env"
$collectLogs = Join-Path $PSScriptRoot "collect-logs.sh"
$installer = Join-Path $PSScriptRoot "install-update.sh"
$publishApk = Join-Path $PSScriptRoot "publish-apk.ps1"
$fullGate = Join-Path $PSScriptRoot "test-full.ps1"
$caddyGate = Join-Path $PSScriptRoot "test-caddy-stack.ps1"
$linuxInstallerGate = Join-Path $PSScriptRoot "test-linux-installer.ps1"
$shellCheckGate = Join-Path $PSScriptRoot "test-shell-scripts.ps1"
$outputDirectory = Join-Path $repoRoot "dist\hosting"
$thirdPartyNotices = Join-Path $repoRoot "android\app\src\main\assets\THIRD_PARTY_NOTICES.txt"

$firstLine = Get-Content -LiteralPath $serverEnv -TotalCount 1
if ($firstLine -notmatch '^ZENPTT_DOMAIN=[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$') {
    throw "The first server.env line must contain ZENPTT_DOMAIN"
}
$serverDomain = $firstLine.Substring($firstLine.IndexOf('=') + 1)

if ($SkipChecks -and $AndroidWorkDirectory) {
    throw "AndroidWorkDirectory requires the checked build path"
}
if ($SkipChecks -and $ReusePublishedApk) {
    throw "ReusePublishedApk requires the checked build path"
}
if (-not $SkipChecks) {
    & $fullGate -AndroidWorkDirectory $AndroidWorkDirectory
    if ($LASTEXITCODE -ne 0) { throw "Full test gate failed" }
    if (-not $ReusePublishedApk) {
        $apkDirectory = if ($AndroidWorkDirectory) {
            Join-Path ([System.IO.Path]::GetFullPath($AndroidWorkDirectory)) "build/_app/outputs/apk/debug"
        } else { $null }
        & $publishApk -SkipBuild -ApkDirectory $apkDirectory
    }
} elseif (-not $ReusePublishedApk) {
    & $publishApk
}
if (-not $ReusePublishedApk -and $LASTEXITCODE -ne 0) { throw "APK publication failed" }
if ($ReusePublishedApk) { Write-Output "Reusing the published APK and release metadata" }
if ($SkipChecks) { & (Join-Path $PSScriptRoot "build-web.ps1") }
if (-not $SkipChecks) {
    & $caddyGate
    if ($LASTEXITCODE -ne 0) { throw "Local Caddy gate failed" }
    & $shellCheckGate
    if ($LASTEXITCODE -ne 0) { throw "ShellCheck failed" }
}

$releaseDirectory = Join-Path $repoRoot "server\releases"
$releasePath = Join-Path $releaseDirectory "release.json"
$release = Get-Content -LiteralPath $releasePath -Raw | ConvertFrom-Json
$apkPath = Join-Path $releaseDirectory ("zenptt-{0}.apk" -f $release.version_code)
$apk = Get-Item -LiteralPath $apkPath
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()

if ($apkHash -ne $release.sha256 -or $apk.Length -ne $release.size_bytes) {
    throw "Published APK does not match release.json"
}

$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "zenptt-hosting-" + [guid]::NewGuid()
)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

try {
    $stage = New-Item -ItemType Directory -Path $tempDirectory
    $stageServer = New-Item -ItemType Directory -Path (Join-Path $stage "server")
    $stageApp = New-Item -ItemType Directory -Path (Join-Path $stageServer "app")
    $stageReleases = New-Item -ItemType Directory -Path (Join-Path $stageServer "releases")
    $stageHeadless = New-Item -ItemType Directory -Path (Join-Path $stage "headless")
    $stageWeb = New-Item -ItemType Directory -Path (Join-Path $stage "web")
    Copy-Item -LiteralPath (Join-Path $repoRoot "web\dist") -Destination $stageWeb -Recurse

    Copy-Item -LiteralPath (Join-Path $repoRoot "compose.yaml") -Destination $stage
    Copy-Item -LiteralPath (Join-Path $repoRoot "Caddyfile") -Destination $stage
    Copy-Item -LiteralPath (Join-Path $repoRoot "index.html") -Destination $stage
    Copy-Item -LiteralPath $thirdPartyNotices -Destination $stage
    $collectLogsText = [System.IO.File]::ReadAllText($collectLogs).Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText(
        (Join-Path $stage "collect-logs.sh"),
        $collectLogsText,
        $utf8NoBom
    )
    Copy-Item -LiteralPath (Join-Path $repoRoot "server\Dockerfile") -Destination $stageServer
    Copy-Item -LiteralPath (Join-Path $repoRoot "server\pyproject.toml") -Destination $stageServer
    Copy-Item -LiteralPath (Join-Path $repoRoot "server\requirements.lock") -Destination $stageServer
    Get-ChildItem -LiteralPath (Join-Path $repoRoot "server\app") -Filter "*.py" -File |
        Copy-Item -Destination $stageApp
    Copy-Item -LiteralPath $releasePath -Destination $stageReleases
    Copy-Item -LiteralPath (Join-Path $repoRoot "headless\Dockerfile") -Destination $stageHeadless
    Copy-Item -LiteralPath (Join-Path $repoRoot "headless\pyproject.toml") -Destination $stageHeadless
    Copy-Item -LiteralPath (Join-Path $repoRoot "headless\requirements.lock") -Destination $stageHeadless
    Copy-Item -LiteralPath (Join-Path $repoRoot "headless\src") -Destination $stageHeadless -Recurse

    $manifest = @(
        "BUNDLE_SCHEMA_VERSION=1"
        "ZENPTT_DOMAIN=$serverDomain"
        "APK_VERSION_CODE=$($release.version_code)"
        "APK_VERSION_NAME=$($release.version_name)"
        "APK_SHA256=$apkHash"
        "APK_SIZE_BYTES=$($apk.Length)"
    ) -join "`n"
    [System.IO.File]::WriteAllText(
        (Join-Path $stage "bundle-manifest.env"),
        $manifest + "`n",
        $utf8NoBom
    )

    if (Test-Path -LiteralPath $outputDirectory) {
        if ((Resolve-Path -LiteralPath $outputDirectory).Path -ne (Join-Path $repoRoot "dist\hosting")) {
            throw "Unexpected hosting output path"
        }
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null

    $zipPath = Join-Path $outputDirectory "zenptt-server.zip"
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $zipPath,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        Get-ChildItem -LiteralPath $stage -Recurse -File |
            Where-Object {
                $_.Extension -ne ".pyc" -and
                $_.FullName -notmatch '[\\/]__pycache__[\\/]'
            } |
            ForEach-Object {
            $entryName = $_.FullName.Substring($stage.FullName.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive,
                $_.FullName,
                $entryName,
                [System.IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }

    $archiveCheck = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        $entryNames = @($archiveCheck.Entries | ForEach-Object FullName)
        $requiredEntries = @(
            "bundle-manifest.env"
            "Caddyfile"
            "index.html"
            "THIRD_PARTY_NOTICES.txt"
            "compose.yaml"
            "collect-logs.sh"
            "server/Dockerfile"
            "server/releases/release.json"
            "headless/Dockerfile"
            "headless/src/zenptt_headless/echo_supervisor.py"
            "web/dist/index.html"
            "web/dist/manifest.webmanifest"
            "web/dist/icons/apple-touch-icon.png"
            "web/dist/icons/zenptt-192.png"
            "web/dist/icons/zenptt-512.png"
            "web/dist/licenses/OPUS-LICENSE.txt"
            "web/dist/licenses/ICONS-LICENSE.txt"
            "web/dist/licenses/THIRD-PARTY-NOTICES.txt"
        )
        foreach ($requiredEntry in $requiredEntries) {
            if ($requiredEntry -notin $entryNames) {
                throw "Hosting ZIP is missing $requiredEntry"
            }
        }
        if (@($entryNames | Where-Object { $_.Contains('\') -or $_.StartsWith('/') }).Count -ne 0) {
            throw "Hosting ZIP contains a non-Linux path"
        }
        foreach ($extension in @("js", "css", "wasm")) {
            if (-not ($entryNames -match "^web/dist/assets/.+\.$extension$")) {
                throw "Hosting ZIP is missing a web $extension asset"
            }
        }
        if (@($entryNames | Where-Object { $_ -match '(^|/)__pycache__/|\.pyc$' }).Count -ne 0) {
            throw "Hosting ZIP contains Python cache files"
        }
        if (@($entryNames | Where-Object {
            $_ -match '(^|/)qrz_bot\.py$' -or
            $_ -match '(^|/)QRZ\.(MP3|pcm)$' -or
            $_ -eq 'headless/compose.yaml' -or
            $_ -match '^examples/qrz_bot/'
        }).Count -ne 0) {
            throw "Hosting ZIP contains QRZ example files"
        }
    } finally {
        $archiveCheck.Dispose()
    }

    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $outputDirectory "zenptt.apk")
    Copy-Item -LiteralPath $serverEnv -Destination (Join-Path $outputDirectory "server.env")

    $installerText = [System.IO.File]::ReadAllText($installer).Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText(
        (Join-Path $outputDirectory "install-update.sh"),
        $installerText,
        $utf8NoBom
    )

    $files = Get-ChildItem -LiteralPath $outputDirectory -File
    $expected = @("zenptt-server.zip", "zenptt.apk", "install-update.sh", "server.env")
    if ($files.Count -ne 4 -or @($files.Name | Where-Object { $_ -notin $expected }).Count -ne 0) {
        throw "Hosting output must contain exactly four files"
    }

    & docker run --rm -v "$($outputDirectory):/bundle:ro" ubuntu:24.04@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90 bash -n /bundle/install-update.sh
    if ($LASTEXITCODE -ne 0) { throw "Linux installer syntax check failed" }
    & docker run --rm -v "$($stage.FullName):/runtime:ro" ubuntu:24.04@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90 bash -n /runtime/collect-logs.sh
    if ($LASTEXITCODE -ne 0) { throw "Linux log collector syntax check failed" }

    if (-not $SkipChecks) {
        & $linuxInstallerGate -BundleDirectory $outputDirectory
        if ($LASTEXITCODE -ne 0) { throw "Linux installer and exact delivery gate failed" }
    }

    Write-Output "Hosting package created: $outputDirectory"
    $files | Sort-Object Name | ForEach-Object {
        Write-Output ("  {0} ({1} bytes)" -f $_.Name, $_.Length)
    }
} finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
