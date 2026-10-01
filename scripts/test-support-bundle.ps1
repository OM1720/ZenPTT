$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "zenptt-support-test-" + [guid]::NewGuid()
)
$runtimeDirectory = Join-Path $tempDirectory "runtime"
$archivePath = Join-Path $tempDirectory "support\zenptt-support-latest.tar.gz"
$extractDirectory = Join-Path $tempDirectory "extracted"
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

try {
    New-Item -ItemType Directory -Path $runtimeDirectory | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot "compose.yaml") -Destination $runtimeDirectory
    Copy-Item -LiteralPath (Join-Path $repoRoot "server.local.env") `
        -Destination (Join-Path $runtimeDirectory "server.env")
    Copy-Item -LiteralPath (Join-Path $repoRoot "server.local.env") `
        -Destination (Join-Path $runtimeDirectory "server.local.env")

    $collector = [System.IO.File]::ReadAllText(
        (Join-Path $PSScriptRoot "collect-logs.sh")
    ).Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText(
        (Join-Path $runtimeDirectory "collect-logs.sh"),
        $collector,
        $utf8NoBom
    )
    [System.IO.File]::WriteAllText(
        (Join-Path $runtimeDirectory "bundle-manifest.env"),
        "APK_VERSION_NAME=test`n",
        $utf8NoBom
    )

    & docker run --rm `
        -v /var/run/docker.sock:/var/run/docker.sock `
        -v "${tempDirectory}:/deployment" `
        -w /deployment/runtime `
        docker:27-cli `
        sh -c "apk add --no-cache bash >/dev/null && bash collect-logs.sh"
    if ($LASTEXITCODE -ne 0) { throw "Linux support bundle collection failed" }
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        throw "Support bundle was not created"
    }

    $entries = @(& tar -tzf $archivePath | ForEach-Object { $_ -replace '^\./', '' })
    if ($LASTEXITCODE -ne 0) { throw "Support bundle cannot be read" }
    foreach ($requiredEntry in @("summary.txt", "server.log", "caddy.log", "diagnostics/")) {
        if ($requiredEntry -notin $entries) {
            throw "Support bundle is missing $requiredEntry"
        }
    }
    if (@($entries | Where-Object { $_ -eq "server.env" -or $_ -like "*/server.env" }).Count -ne 0) {
        throw "Support bundle must not contain server.env"
    }

    New-Item -ItemType Directory -Path $extractDirectory | Out-Null
    & tar -xzf $archivePath -C $extractDirectory
    if ($LASTEXITCODE -ne 0) { throw "Support bundle extraction failed" }
    if ((Get-Item -LiteralPath (Join-Path $extractDirectory "server.log")).Length -eq 0) {
        throw "Collected server log is empty"
    }
    if (@(Get-ChildItem -LiteralPath (Join-Path $extractDirectory "diagnostics") -Filter "*.json").Count -eq 0) {
        throw "Support bundle contains no client diagnostic reports"
    }

    Write-Output "Support bundle collection passed"
} finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
