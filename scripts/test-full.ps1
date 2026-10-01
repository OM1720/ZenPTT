param(
    [switch]$WithInstrumentation,
    [switch]$WithLiveStack,
    [string]$AndroidWorkDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $repoRoot ".venv\Scripts\python.exe"
$androidRoot = Join-Path $repoRoot "android"
$gradle = Join-Path $androidRoot "gradlew.bat"
$gradleArguments = @("--console=plain")
$appBuildRoot = Join-Path $androidRoot "app/build"

if ($AndroidWorkDirectory) {
    $workRoot = [System.IO.Path]::GetFullPath($AndroidWorkDirectory)
    New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
    $initScript = Join-Path $workRoot "zenptt-build.init.gradle"
    $buildRoot = (Join-Path $workRoot "build").Replace("\", "/").Replace("'", "\'")
    @"
gradle.beforeProject { project ->
    project.layout.buildDirectory.set(new File('$buildRoot', project.path.replace(':', '_')))
}
"@ | Set-Content -LiteralPath $initScript -Encoding utf8
    $gradleArguments += @("--init-script", $initScript, "--project-cache-dir", (Join-Path $workRoot "project-cache"))
    $appBuildRoot = Join-Path $workRoot "build/_app"
}

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project virtual environment is missing: $python"
}

if (-not $env:ANDROID_HOME) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path -LiteralPath $defaultSdk -PathType Container) {
        $env:ANDROID_HOME = $defaultSdk
    }
}
if (-not $env:ANDROID_HOME) {
    throw "ANDROID_HOME is required for Android checks"
}

Push-Location $repoRoot
try {
    & $python -m pytest server\tests -q --cov=server\app --cov-report=term-missing --cov-fail-under=90
    if ($LASTEXITCODE -ne 0) { throw "Server tests failed" }

    & $python -m ruff check server\app server\tests
    if ($LASTEXITCODE -ne 0) { throw "Ruff failed" }

    & (Join-Path $PSScriptRoot "test-headless.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Headless checks failed" }

    & (Join-Path $PSScriptRoot "test-icon-assets.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Icon asset checks failed" }

    & (Join-Path $PSScriptRoot "test-web.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Production web checks failed" }

    Push-Location $androidRoot
    try {
        & $gradle @gradleArguments `
            :headset:testDebugUnitTest `
            :headset:lintDebug `
            :app:testDebugUnitTest `
            :app:lintDebug `
            :app:assembleDebug `
            :app:assembleAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "Android JVM, lint or assembly checks failed" }

        $aapt = Join-Path $env:ANDROID_HOME "build-tools\35.0.0\aapt.exe"
        if (-not (Test-Path -LiteralPath $aapt -PathType Leaf)) {
            throw "Android aapt is missing: $aapt"
        }
        $applicationApk = Join-Path $appBuildRoot "outputs\apk\debug\app-debug.apk"
        $instrumentationApk = Join-Path $appBuildRoot (
            "outputs\apk\androidTest\debug\app-debug-androidTest.apk"
        )
        $applicationBadging = (& $aapt dump badging $applicationApk | Select-Object -First 1)
        $instrumentationBadging = (& $aapt dump badging $instrumentationApk | Select-Object -First 1)
        $applicationVersion = [regex]::Match($applicationBadging, "versionCode='([0-9]+)'")
        $instrumentationVersion = [regex]::Match($instrumentationBadging, "versionCode='([0-9]+)'")
        if (-not $applicationVersion.Success -or -not $instrumentationVersion.Success) {
            throw "Both Android APKs must have an explicit versionCode"
        }
        if ($applicationVersion.Groups[1].Value -eq $instrumentationVersion.Groups[1].Value) {
            throw "Application and instrumentation APKs must use distinct versionCode values"
        }

        if ($WithInstrumentation) {
            & $gradle @gradleArguments :app:connectedDebugAndroidTest
            if ($LASTEXITCODE -ne 0) { throw "Android instrumentation tests failed" }
        }
    } finally {
        Pop-Location
    }

    & (Join-Path $PSScriptRoot "test-publish-apk.ps1") -ApkDirectory (Join-Path $appBuildRoot "outputs/apk/debug")
    if ($LASTEXITCODE -ne 0) { throw "Immutable APK publisher checks failed" }

    if ($WithLiveStack) {
        & (Join-Path $PSScriptRoot "test-live-stack.ps1")
        if ($LASTEXITCODE -ne 0) { throw "Live stack checks failed" }
    }
} finally {
    Pop-Location
}

Write-Output "Full local test gate passed"
