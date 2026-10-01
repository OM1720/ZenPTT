param(
    [switch]$KeepRunning,
    [int]$IdleSeconds = 2
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $repoRoot ".venv\Scripts\python.exe"
$composeArguments = @("compose", "--env-file", "server.local.env")
$releaseMetadata = Join-Path $repoRoot "server\releases\release.json"
$release = if (Test-Path -LiteralPath $releaseMetadata) {
    Get-Content -LiteralPath $releaseMetadata -Raw | ConvertFrom-Json
} else { $null }
$publishedApk = if ($release) {
    Join-Path $repoRoot ("server\releases\zenptt-{0}.apk" -f $release.version_code)
} else { $null }
$diagnosticsClientLimitLine = Get-Content -LiteralPath (Join-Path $repoRoot "server.local.env") |
    Where-Object { $_ -match '^DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE=' } |
    Select-Object -First 1
if (-not $diagnosticsClientLimitLine) {
    throw "DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE is missing"
}
$diagnosticsClientLimit = [int]$diagnosticsClientLimitLine.Substring(
    $diagnosticsClientLimitLine.IndexOf('=') + 1
)
Push-Location $repoRoot
try {
    if (-not $publishedApk -or -not (Test-Path -LiteralPath $publishedApk -PathType Leaf)) {
        & (Join-Path $PSScriptRoot "publish-apk.ps1")
        if ($LASTEXITCODE -ne 0) { throw "APK publication failed" }
        $release = Get-Content -LiteralPath $releaseMetadata -Raw | ConvertFrom-Json
        $publishedApk = Join-Path $repoRoot ("server\releases\zenptt-{0}.apk" -f $release.version_code)
    }

    & docker @composeArguments config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Compose configuration is invalid" }

    $config = (& docker @composeArguments config --format json) | ConvertFrom-Json
    if ($null -ne $config.services."zenptt-server".ports) {
        throw "The application server port must not be published"
    }
    if (@($config.services.caddy.ports).Count -ne 2) {
        throw "Caddy must publish HTTP and HTTPS ports"
    }
    $httpPort = @($config.services.caddy.ports |
        Where-Object { $_.target -eq 80 -and $_.protocol -eq "tcp" })
    if ($httpPort.Count -ne 1 -or $httpPort[0].published -notmatch '^\d+$') {
        throw "Caddy must publish one HTTP port"
    }
    $serverUrl = "http://127.0.0.1:$($httpPort[0].published)"
    if ($config.services."zenptt-server".mem_limit -ne 805306368 -or
        $config.services."zenptt-server".pids_limit -ne 128) {
        throw "Application server resource limits are invalid"
    }
    if ($config.services.caddy.mem_limit -ne 268435456 -or
        $config.services.caddy.pids_limit -ne 64) {
        throw "Caddy resource limits are invalid"
    }
    if ($config.services."echo-supervisor".mem_limit -ne 268435456 -or
        $config.services."echo-supervisor".pids_limit -ne 32) {
        throw "Echo supervisor resource limits are invalid"
    }
    $serverConfig = $config.services."zenptt-server"
    $caddyConfig = $config.services.caddy
    if (-not $serverConfig.read_only -or
        $serverConfig.cap_drop -notcontains "ALL" -or
        $serverConfig.security_opt -notcontains "no-new-privileges:true" -or
        $serverConfig.tmpfs -notcontains "/tmp:rw,noexec,nosuid,nodev,size=16m"
    ) {
        throw "Application server filesystem and privilege restrictions are invalid"
    }
    if (-not $caddyConfig.read_only -or
        $caddyConfig.cap_drop -notcontains "ALL" -or
        $caddyConfig.cap_add -notcontains "NET_BIND_SERVICE" -or
        $caddyConfig.security_opt -notcontains "no-new-privileges:true" -or
        $caddyConfig.tmpfs -notcontains "/tmp:rw,noexec,nosuid,nodev,size=16m"
    ) {
        throw "Caddy filesystem and privilege restrictions are invalid"
    }

    & docker @composeArguments up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Compose startup failed" }

    $healthy = $false
    foreach ($attempt in 1..30) {
        try {
            $health = Invoke-RestMethod -Uri "$serverUrl/health" -TimeoutSec 3
            if ($health.status -eq "ok") {
                $healthy = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $healthy) { throw "Caddy health check failed" }

    $homeResponse = Invoke-WebRequest -UseBasicParsing -Uri "$serverUrl/" -TimeoutSec 3
    if ($homeResponse.Content -notmatch '/web/assets/' -or
        $homeResponse.Headers["Cache-Control"] -ne "no-cache") {
        throw "Web home page is invalid"
    }
    $expectedHeaders = @{
        "Strict-Transport-Security" = "max-age=31536000"
        "X-Content-Type-Options" = "nosniff"
        "Referrer-Policy" = "no-referrer"
    }
    foreach ($entry in $expectedHeaders.GetEnumerator()) {
        if ($homeResponse.Headers[$entry.Key] -ne $entry.Value) {
            throw "Missing or invalid $($entry.Key) header"
        }
    }
    if ($homeResponse.Headers["Content-Security-Policy"] -notmatch "frame-ancestors 'none'" -or
        $homeResponse.Headers["Server"]) {
        throw "Caddy security headers are invalid"
    }
    $webResponse = Invoke-WebRequest -UseBasicParsing -Uri "$serverUrl/web/" -TimeoutSec 3
    if ($webResponse.Content -ne $homeResponse.Content) { throw "Root and /web/ must serve the same client" }
    if ($webResponse.Content -notmatch '/web/assets/' -or $webResponse.Headers["Cache-Control"] -ne "no-cache") {
        throw "Production web entry or cache policy is invalid"
    }
    if ($webResponse.Content -notmatch '/web/manifest\.webmanifest' -or
        $webResponse.Content -notmatch '/web/icons/apple-touch-icon\.png') {
        throw "Production web entry is missing install metadata"
    }
    if ($webResponse.Headers["Content-Security-Policy"] -notmatch "manifest-src 'self'" -or
        $webResponse.Headers["Content-Security-Policy"] -notmatch "img-src 'self'") {
        throw "Production web CSP does not allow install metadata"
    }
    $webManifestResponse = Invoke-WebRequest -UseBasicParsing -Uri "$serverUrl/web/manifest.webmanifest" -TimeoutSec 3
    $webManifestText = if ($webManifestResponse.Content -is [byte[]]) {
        [System.Text.Encoding]::UTF8.GetString($webManifestResponse.Content)
    } else {
        $webManifestResponse.Content
    }
    $webManifest = $webManifestText | ConvertFrom-Json
    if ($webManifestResponse.Headers["Cache-Control"] -ne "no-cache" -or
        $webManifestResponse.Headers["Content-Type"] -notmatch '^application/manifest\+json' -or
        $webManifest.id -ne "/web/" -or $webManifest.start_url -ne "/web/" -or
        $webManifest.scope -ne "/" -or $webManifest.display -ne "standalone") {
        throw "Web app manifest is invalid"
    }
    foreach ($webIcon in @("apple-touch-icon.png", "zenptt-192.png", "zenptt-512.png")) {
        $iconResponse = Invoke-WebRequest -UseBasicParsing -Uri "$serverUrl/web/icons/$webIcon" -TimeoutSec 3
        if ($iconResponse.Headers["Content-Type"] -notmatch '^image/png' -or
            $iconResponse.Headers["Cache-Control"] -ne "no-cache") {
            throw "Web app icon is invalid: $webIcon"
        }
    }

    $healthResponse = Invoke-WebRequest -UseBasicParsing -Uri "$serverUrl/health" -TimeoutSec 3
    foreach ($entry in $expectedHeaders.GetEnumerator()) {
        if ($healthResponse.Headers[$entry.Key] -ne $entry.Value) {
            throw "Missing or invalid $($entry.Key) header on proxied responses"
        }
    }
    if ($healthResponse.Headers["Server"]) {
        throw "Proxied responses must not expose a Server header"
    }

    $caddyLogs = & docker @composeArguments logs --since 2m caddy
    $caddyLogText = $caddyLogs -join "`n"
    if ($LASTEXITCODE -ne 0 -or $caddyLogText -notmatch '"uri":"/health"') {
        throw "Caddy access log does not contain the health request"
    }

    Add-Type -AssemblyName System.Net.Http
    $httpClient = [System.Net.Http.HttpClient]::new()
    $openApiResponse = $httpClient.GetAsync("$serverUrl/openapi.json").GetAwaiter().GetResult()
    try {
        if ([int]$openApiResponse.StatusCode -ne 404) {
            throw "OpenAPI schema must not be public"
        }
    } finally {
        $openApiResponse.Dispose()
    }
    foreach ($internalPath in @("/internal", "/internal/echo/status", "/internal/echo/control")) {
        $internalResponse = $httpClient.GetAsync("$serverUrl$internalPath").GetAwaiter().GetResult()
        try {
            if ([int]$internalResponse.StatusCode -ne 404) {
                throw "Internal route must not be public: $internalPath"
            }
        } finally {
            $internalResponse.Dispose()
        }
    }
    $rangeRequest = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        "$serverUrl/app/releases/$($release.version_code)/download"
    )
    $rangeRequest.Headers.Range = [System.Net.Http.Headers.RangeHeaderValue]::new(0, 3)
    try {
        $rangeResponse = $httpClient.SendAsync($rangeRequest).GetAwaiter().GetResult()
        $rangeContent = $rangeResponse.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        if ([int]$rangeResponse.StatusCode -ne 206 -or
            $rangeContent.Length -ne 4 -or
            $rangeResponse.Content.Headers.ContentRange.ToString() -ne "bytes 0-3/$((Get-Item -LiteralPath $publishedApk).Length)") {
            throw "Caddy must preserve Range and return a partial APK"
        }
    } finally {
        $rangeRequest.Dispose()
        $httpClient.Dispose()
    }

    & (Join-Path $PSScriptRoot "test-live-stack.ps1") -ServerUrl $serverUrl -IdleSeconds $IdleSeconds
    if ($LASTEXITCODE -ne 0) { throw "Live Caddy stack checks failed" }

    $serverLogs = & docker @composeArguments logs --since 2m zenptt-server
    $serverLogText = $serverLogs -join "`n"
    if ($LASTEXITCODE -ne 0 -or
        $serverLogText -notmatch 'join session=' -or
        $serverLogText -notmatch 'grant session=') {
        throw "Application session events are missing from server logs"
    }

    & docker @composeArguments restart zenptt-server
    if ($LASTEXITCODE -ne 0) { throw "Server restart before forwarding-header test failed" }

    $healthy = $false
    foreach ($attempt in 1..30) {
        try {
            $health = Invoke-RestMethod -Uri "$serverUrl/health" -TimeoutSec 3
            if ($health.status -eq "ok") {
                $healthy = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $healthy) { throw "Caddy health check after server restart failed" }

    & $python (Join-Path $PSScriptRoot "test-forwarded-client-limit.py") `
        $serverUrl $diagnosticsClientLimit
    if ($LASTEXITCODE -ne 0) { throw "Forwarded client limit check failed" }

    & (Join-Path $PSScriptRoot "test-support-bundle.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Support bundle checks failed" }

    $commandLine = & docker @composeArguments exec -T zenptt-server python -c "print(open('/proc/1/cmdline','rb').read().replace(b'\0',b' ').decode())"
    if ($LASTEXITCODE -ne 0 -or $commandLine -notmatch "python.*-m app.run") {
        throw "Server is not running through the resource-limited launcher"
    }

    Write-Output "Local Caddy stack passed at $serverUrl"
} finally {
    if (-not $KeepRunning) {
        & docker @composeArguments down
    }
    Pop-Location
}
