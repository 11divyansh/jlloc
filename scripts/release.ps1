param(
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradlew = Join-Path $repoRoot "gradlew.bat"
$releaseDir = Join-Path $repoRoot "build\release"

Push-Location $repoRoot
try {
    & $gradlew clean test ":jlloc-daemon:installDist" ":jlloc-cli:installDist" `
        ":jlloc-daemon:distZip" ":jlloc-cli:distZip"
    if ($LASTEXITCODE -ne 0) { throw "Release build failed." }

    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    Get-ChildItem -Path (Join-Path $repoRoot "jlloc-daemon\build\distributions"), `
        (Join-Path $repoRoot "jlloc-cli\build\distributions") -Filter "*.zip" |
        Copy-Item -Destination $releaseDir -Force

    $checksums = Get-ChildItem $releaseDir -Filter "*.zip" |
        Get-FileHash -Algorithm SHA256 |
        ForEach-Object { "$($_.Hash.ToLowerInvariant())  $($_.Path | Split-Path -Leaf)" }
    Set-Content -Path (Join-Path $releaseDir "SHA256SUMS.txt") -Value $checksums
    Write-Host "Release artifacts written to $releaseDir for v$Version"
} finally {
    Pop-Location
}
