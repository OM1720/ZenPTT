param()

$ErrorActionPreference = "Stop"
$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    "zenptt-shellcheck-" + [guid]::NewGuid()
)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$sources = @(
    "install-update.sh"
    "collect-logs.sh"
    "test-install-update-bootstrap.sh"
    "test-install-update-linux.sh"
)

try {
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    foreach ($name in $sources) {
        $source = Join-Path $PSScriptRoot $name
        $target = Join-Path $tempDirectory $name
        $text = [System.IO.File]::ReadAllText($source).Replace("`r`n", "`n")
        [System.IO.File]::WriteAllText($target, $text, $utf8NoBom)
    }

    $mount = $tempDirectory + ":/scripts:ro"
    $shellScripts = @($sources | ForEach-Object { "/scripts/$_" })
    & docker run --rm -v $mount koalaman/shellcheck:v0.10.0 @shellScripts
    if ($LASTEXITCODE -ne 0) { throw "ShellCheck failed" }

    Write-Output "ShellCheck passed"
} finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
