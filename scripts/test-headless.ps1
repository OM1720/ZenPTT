param([switch]$WithDocker)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $repoRoot ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $python = Join-Path $repoRoot ".venv/bin/python"
}
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project virtual environment is missing: $python"
}
if (-not (Get-Command ffmpeg -CommandType Application -ErrorAction SilentlyContinue)) {
    throw "FFmpeg is required on PATH for the offline MP3 tests. Install FFmpeg before running the headless gate."
}

Push-Location $repoRoot
try {
    $previousPythonPath = $env:PYTHONPATH
    $env:PYTHONPATH = Join-Path $repoRoot "headless/src"
    try {
        & $python scripts/run-bounded.py 120 $python -m pytest `
            headless/tests examples/qrz_bot/tests scripts/poor-link -q -p no:cov
        if ($LASTEXITCODE -ne 0) { throw "Headless tests failed" }
        & $python scripts/run-bounded.py 120 $python -m ruff check `
            headless/src headless/tests examples/qrz_bot `
            scripts/headless-live-proxy.py `
            scripts/headless-tail-handler.py scripts/test-headless-live.py `
            scripts/test-headless-contract.py `
            scripts/test-headless-audio.py `
            scripts/test-headless-signal.py scripts/run-bounded.py scripts/poor-link
        if ($LASTEXITCODE -ne 0) { throw "Headless Ruff failed" }
        & (Join-Path $PSScriptRoot "test-headless-live.ps1") -CheckMetadataOnly
    } finally {
        $env:PYTHONPATH = $previousPythonPath
    }
    if ($WithDocker) {
        & $python scripts/run-bounded.py 600 docker build --pull=false `
            --tag zenptt-headless-test ./headless
        if ($LASTEXITCODE -ne 0) { throw "Headless container build failed" }
        & $python scripts/run-bounded.py 120 docker run --rm `
            -v "${repoRoot}/scripts/test-headless-audio.py:/test-audio.py:ro" `
            -v "${repoRoot}/scripts/test-headless-live.py:/test.py:ro" `
            -v "${repoRoot}/examples/qrz_bot/assets/QRZ.pcm:/audio/QRZ.pcm:ro" `
            zenptt-headless-test python /test-audio.py
        if ($LASTEXITCODE -ne 0) { throw "Headless container codec test failed" }
        & $python scripts/run-bounded.py 120 docker run --rm `
            -v "${repoRoot}/scripts/test-headless-signal.py:/test-signal.py:ro" `
            -v "${repoRoot}/examples/qrz_bot/qrz_bot.py:/example/qrz_bot.py:ro" `
            -e PYTHONPATH=/example:/app/src `
            zenptt-headless-test python /test-signal.py
        if ($LASTEXITCODE -ne 0) { throw "Headless Linux shutdown test failed" }
        & $python scripts/run-bounded.py 120 docker run --rm `
            zenptt-headless-test python -c `
            "import importlib.util,pathlib; assert importlib.util.find_spec('zenptt_headless.qrz_bot') is None; assert not pathlib.Path('/audio/QRZ.pcm').exists()"
        if ($LASTEXITCODE -ne 0) { throw "Production headless image contains QRZ example files" }
    }
} finally {
    Pop-Location
}

Write-Output "Headless test gate passed"
