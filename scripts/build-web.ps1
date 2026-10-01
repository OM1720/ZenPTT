param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $repoRoot "web"
$container = "zenptt-web-export-" + [guid]::NewGuid().ToString("N")
$created = $false
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw "Docker Engine is required for the pinned web build" }
try {
    & docker build --tag zenptt-web-build $webRoot
    if ($LASTEXITCODE -ne 0) { throw "Pinned web build or checks failed" }
    & docker create --name $container zenptt-web-build | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to create web export container" }
    $created = $true
    foreach ($directory in @("dist", "src/generated")) {
        $target = [System.IO.Path]::GetFullPath((Join-Path $webRoot $directory))
        if (-not $target.StartsWith($webRoot + [System.IO.Path]::DirectorySeparatorChar)) {
            throw "Web export target is outside web/"
        }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        New-Item -ItemType Directory -Path $target | Out-Null
        & docker cp "${container}:/app/$directory/." $target
        if ($LASTEXITCODE -ne 0) { throw "Unable to export $directory" }
    }
    Write-Output "Verified Linux web build exported to web/dist"
} finally {
    if ($created) { & docker rm $container | Out-Null }
}
