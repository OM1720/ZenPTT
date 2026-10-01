param([string]$BundleDirectory)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$installerSource = Join-Path $PSScriptRoot "install-update.sh"
$bootstrapTestSource = Join-Path $PSScriptRoot "test-install-update-bootstrap.sh"
$linuxTestSource = Join-Path $PSScriptRoot "test-install-update-linux.sh"
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "zenptt-linux-installer-" + [guid]::NewGuid()
)
$bootstrapContainerName = "zenptt-bootstrap-gate-" + [guid]::NewGuid().ToString("N")
$containerName = "zenptt-installer-gate-" + [guid]::NewGuid().ToString("N")
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$bootstrapContainerStarted = $false
$containerStarted = $false

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-LfFile {
    param(
        [string]$Path,
        [string]$Content
    )

    $crlf = [string][char]13 + [char]10
    $lf = [string][char]10
    $normalized = $Content.Replace($crlf, $lf)
    if (-not $normalized.EndsWith($lf)) {
        $normalized += $lf
    }
    [System.IO.File]::WriteAllText($Path, $normalized, $utf8NoBom)
}

function New-TestBundle {
    param(
        [string]$Name,
        [int]$VersionCode,
        [string]$VersionName,
        [switch]$BrokenHealth,
        [switch]$BadReleaseSize,
        [string]$ApkSuffix = ""
    )

    $bundleDirectory = New-Item -ItemType Directory -Path (Join-Path $tempDirectory $Name)
    $stage = New-Item -ItemType Directory -Path (
        Join-Path $tempDirectory ("stage-" + [guid]::NewGuid())
    )

    try {
        $stageServer = New-Item -ItemType Directory -Path (Join-Path $stage "server")
        $stageApp = New-Item -ItemType Directory -Path (Join-Path $stageServer "app")
        $stageReleases = New-Item -ItemType Directory -Path (Join-Path $stageServer "releases")
        $stageHeadless = New-Item -ItemType Directory -Path (Join-Path $stage "headless")
        $stageWeb = New-Item -ItemType Directory -Path (Join-Path $stage "web")
        Copy-Item -LiteralPath (Join-Path $repoRoot "web\dist") -Destination $stageWeb -Recurse
        $webEntry = Join-Path $stageWeb "dist\index.html"
        Write-LfFile $webEntry ([System.IO.File]::ReadAllText($webEntry) + "`n<!-- Installer fixture $Name -->")

        Copy-Item -LiteralPath (Join-Path $repoRoot "compose.yaml") -Destination $stage
        # Nested Docker can expose a threaded cgroup tree that cannot accept
        # resource controllers. Production bundles never include this override.
        Write-LfFile (Join-Path $stage "compose.override.yaml") @"
services:
  zenptt-server:
    mem_limit: 0
    pids_limit: -1
  echo-supervisor:
    mem_limit: 0
    pids_limit: -1
  caddy:
    mem_limit: 0
    pids_limit: -1
"@
        Copy-Item -LiteralPath (Join-Path $repoRoot "index.html") -Destination $stage
        Copy-Item -LiteralPath (Join-Path $repoRoot "server\Dockerfile") -Destination $stageServer
        Copy-Item -LiteralPath (Join-Path $repoRoot "server\pyproject.toml") -Destination $stageServer
        Copy-Item -LiteralPath (Join-Path $repoRoot "server\requirements.lock") -Destination $stageServer
        Get-ChildItem -LiteralPath (Join-Path $repoRoot "server\app") -Filter "*.py" -File |
            Copy-Item -Destination $stageApp
        Copy-Item -LiteralPath (Join-Path $repoRoot "headless\Dockerfile") -Destination $stageHeadless
        Copy-Item -LiteralPath (Join-Path $repoRoot "headless\pyproject.toml") -Destination $stageHeadless
        Copy-Item -LiteralPath (Join-Path $repoRoot "headless\requirements.lock") -Destination $stageHeadless
        Copy-Item -LiteralPath (Join-Path $repoRoot "headless\src") -Destination $stageHeadless -Recurse

        $caddyText = [System.IO.File]::ReadAllText((Join-Path $repoRoot "Caddyfile"))
        $caddyText = $caddyText.Replace('{$ZENPTT_DOMAIN} {', ':80 {')
        Write-LfFile (Join-Path $stage "Caddyfile") $caddyText
        Write-LfFile (Join-Path $stage "collect-logs.sh") (
            [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot "collect-logs.sh"))
        )

        if ($BrokenHealth) {
            $mainPath = Join-Path $stageApp "main.py"
            $mainText = [System.IO.File]::ReadAllText($mainPath)
            $healthyLine = 'return {"status": "ok"}'
            if (-not $mainText.Contains($healthyLine)) {
                throw "Unable to create broken health fixture"
            }
            $mainText = $mainText.Replace(
                $healthyLine,
                'raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "broken test release")'
            )
            Write-LfFile $mainPath $mainText
        }

        $apkBytes = [System.Text.Encoding]::UTF8.GetBytes(
            "ZenPTT installer lifecycle APK $VersionCode$ApkSuffix"
        )
        $apkPath = Join-Path $bundleDirectory "zenptt.apk"
        [System.IO.File]::WriteAllBytes($apkPath, $apkBytes)
        $apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $releaseSize = if ($BadReleaseSize) { $apkBytes.Length + 1 } else { $apkBytes.Length }

        $release = [ordered]@{
            version_code = $VersionCode
            version_name = $VersionName
            sha256 = $apkHash
            size_bytes = $releaseSize
        } | ConvertTo-Json
        Write-LfFile (Join-Path $stageReleases "release.json") $release

        $manifest = @(
            "BUNDLE_SCHEMA_VERSION=1"
            "ZENPTT_DOMAIN=ptt.test"
            "APK_VERSION_CODE=$VersionCode"
            "APK_VERSION_NAME=$VersionName"
            "APK_SHA256=$apkHash"
            "APK_SIZE_BYTES=$($apkBytes.Length)"
        ) -join ([string][char]10)
        Write-LfFile (Join-Path $stage "bundle-manifest.env") $manifest

        $serverEnv = [System.IO.File]::ReadAllText((Join-Path $repoRoot "server.env.example"))
        $serverEnv = [regex]::Replace(
            $serverEnv,
            '^ZENPTT_DOMAIN=.*',
            'ZENPTT_DOMAIN=ptt.test'
        )
        Write-LfFile (Join-Path $bundleDirectory "server.env") $serverEnv
        Write-LfFile (Join-Path $bundleDirectory "install-update.sh") (
            [System.IO.File]::ReadAllText($installerSource)
        )

        $zipPath = Join-Path $bundleDirectory "zenptt-server.zip"
        $archive = [System.IO.Compression.ZipFile]::Open(
            $zipPath,
            [System.IO.Compression.ZipArchiveMode]::Create
        )
        try {
            Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object {
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
    } finally {
        if (Test-Path -LiteralPath $stage) {
            Remove-Item -LiteralPath $stage -Recurse -Force
        }
    }
}

function Copy-TestBundle {
    param(
        [string]$SourceName,
        [string]$TargetName
    )

    Copy-Item -LiteralPath (Join-Path $tempDirectory $SourceName) -Destination (Join-Path $tempDirectory $TargetName) -Recurse
}

try {
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    if ($BundleDirectory) {
        $actual = New-Item -ItemType Directory -Path (Join-Path $tempDirectory "actual")
        foreach ($name in @("install-update.sh", "zenptt-server.zip", "zenptt.apk", "server.env")) {
            Copy-Item -LiteralPath (Join-Path $BundleDirectory $name) -Destination $actual
        }
    }
    New-TestBundle -Name "good-v1" -VersionCode 1 -VersionName "0.6.0"
    New-TestBundle -Name "good-v2" -VersionCode 2 -VersionName "0.6.1"
    New-TestBundle -Name "conflict-v2" -VersionCode 2 -VersionName "0.6.1" -ApkSuffix " conflict"
    New-TestBundle -Name "broken-v3" -VersionCode 3 -VersionName "0.6.2" -BrokenHealth
    New-TestBundle -Name "bad-release-size" -VersionCode 2 -VersionName "0.6.1" -BadReleaseSize

    Copy-TestBundle "good-v2" "bad-zip"
    [System.IO.File]::WriteAllBytes(
        (Join-Path $tempDirectory "bad-zip\zenptt-server.zip"),
        [System.Text.Encoding]::UTF8.GetBytes("damaged")
    )

    Copy-TestBundle "good-v2" "bad-qrz"
    $qrzArchive = [System.IO.Compression.ZipFile]::Open(
        (Join-Path $tempDirectory "bad-qrz\zenptt-server.zip"),
        [System.IO.Compression.ZipArchiveMode]::Update
    )
    try {
        $qrzEntry = $qrzArchive.CreateEntry("examples/qrz_bot/qrz_bot.py")
        $qrzWriter = [System.IO.StreamWriter]::new($qrzEntry.Open(), $utf8NoBom)
        try { $qrzWriter.WriteLine("# forbidden QRZ fixture") } finally { $qrzWriter.Dispose() }
    } finally {
        $qrzArchive.Dispose()
    }

    Copy-TestBundle "good-v2" "bad-apk"
    [System.IO.File]::WriteAllBytes(
        (Join-Path $tempDirectory "bad-apk\zenptt.apk"),
        [System.Text.Encoding]::UTF8.GetBytes("foreign apk")
    )

    Copy-TestBundle "good-v2" "bad-dns"
    $badDnsPath = Join-Path $tempDirectory "bad-dns\server.env"
    $badDns = [System.IO.File]::ReadAllText($badDnsPath).Replace(
        "ZENPTT_DOMAIN=ptt.test",
        "ZENPTT_DOMAIN=other.test"
    )
    Write-LfFile $badDnsPath $badDns

    Copy-TestBundle "good-v2" "missing-apk"
    Remove-Item -LiteralPath (Join-Path $tempDirectory "missing-apk\zenptt.apk") -Force

    Copy-TestBundle "good-v2" "missing-web"
    $brokenWeb = [System.IO.Compression.ZipFile]::Open(
        (Join-Path $tempDirectory "missing-web\zenptt-server.zip"),
        [System.IO.Compression.ZipArchiveMode]::Update
    )
    try { $brokenWeb.GetEntry("web/dist/index.html").Delete() } finally { $brokenWeb.Dispose() }

    Write-LfFile (Join-Path $tempDirectory "test-install-update-bootstrap.sh") (
        [System.IO.File]::ReadAllText($bootstrapTestSource)
    )
    Write-LfFile (Join-Path $tempDirectory "test-install-update-linux.sh") (
        [System.IO.File]::ReadAllText($linuxTestSource)
    )

    & docker info | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker is unavailable" }

    & docker run --detach --privileged --name $bootstrapContainerName ubuntu:24.04@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90 sleep infinity | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to start clean Ubuntu bootstrap container" }
    $bootstrapContainerStarted = $true

    & docker exec $bootstrapContainerName mkdir -p /fixtures
    if ($LASTEXITCODE -ne 0) { throw "Unable to create Ubuntu fixture directory" }
    $bootstrapFixtureDestination = $bootstrapContainerName + ":/fixtures"
    & docker cp "$tempDirectory\." $bootstrapFixtureDestination
    if ($LASTEXITCODE -ne 0) { throw "Unable to copy Ubuntu bootstrap fixtures" }

    & docker exec $bootstrapContainerName bash /fixtures/test-install-update-bootstrap.sh
    if ($LASTEXITCODE -ne 0) { throw "Clean Ubuntu bootstrap gate failed" }
    Write-Output "Clean Ubuntu bootstrap gate passed"
    # Release the first nested Docker engine before starting the lifecycle gate.
    & docker rm --force $bootstrapContainerName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to stop the completed Ubuntu bootstrap container" }
    $bootstrapContainerStarted = $false

    & docker run --detach --privileged --name $containerName --env DOCKER_TLS_CERTDIR= docker:27.5.1-dind@sha256:aa3df78ecf320f5fafdce71c659f1629e96e9de0968305fe1de670e0ca9176ce | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to start Docker-in-Docker" }
    $containerStarted = $true

    $dockerReady = $false
    foreach ($attempt in 1..60) {
        & docker exec $containerName sh -c "docker info >/dev/null 2>&1"
        if ($LASTEXITCODE -eq 0) {
            $dockerReady = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $dockerReady) { throw "Docker-in-Docker did not become ready" }

    & docker exec $containerName apk add --no-cache bash curl unzip coreutils libc-utils python3
    if ($LASTEXITCODE -ne 0) { throw "Unable to prepare Linux test container" }

    & docker exec $containerName mkdir -p /fixtures
    if ($LASTEXITCODE -ne 0) { throw "Unable to create fixture directory" }
    $fixtureDestination = $containerName + ":/fixtures"
    & docker cp "$tempDirectory\." $fixtureDestination
    if ($LASTEXITCODE -ne 0) { throw "Unable to copy Linux test fixtures" }

    & docker exec $containerName bash /fixtures/test-install-update-linux.sh
    if ($LASTEXITCODE -ne 0) { throw "Linux installer lifecycle gate failed" }

    Write-Output "Linux installer lifecycle gate passed"
} finally {
    if ($containerStarted) {
        & docker rm --force $containerName | Out-Null
    }
    if ($bootstrapContainerStarted) {
        & docker rm --force $bootstrapContainerName | Out-Null
    }
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
