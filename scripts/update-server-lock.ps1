$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$dockerfile = Join-Path $repoRoot "server\Dockerfile"
$fromLine = Get-Content -LiteralPath $dockerfile -TotalCount 1

if ($fromLine -notmatch '^FROM (?<image>\S+@sha256:[0-9a-f]{64})$') {
    throw "The server Dockerfile must use a SHA-256-pinned Python base image"
}

$pythonImage = $Matches.image
$pipToolsVersion = "7.6.0"
$compileCommand = @"
set -eu
python -m pip install \
    --disable-pip-version-check \
    --root-user-action=ignore \
    --no-cache-dir \
    pip-tools==$pipToolsVersion >/dev/null
mkdir -p /tmp/server
cp server/pyproject.toml /tmp/server/pyproject.toml
python -m piptools compile \
    --quiet \
    --generate-hashes \
    --all-build-deps \
    --allow-unsafe \
    --strip-extras \
    --no-header \
    --no-annotate \
    --output-file=server/requirements.lock \
    /tmp/server/pyproject.toml
"@
$compileCommand = $compileCommand.Replace("`r`n", "`n")

Push-Location $repoRoot
try {
    & docker run --rm `
        --volume "${repoRoot}:/work" `
        --workdir /work `
        $pythonImage `
        sh -c $compileCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Server lock generation failed"
    }
} finally {
    Pop-Location
}

Write-Output "Updated server/requirements.lock"
