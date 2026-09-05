param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Args
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$stateDir = Join-Path $env:USERPROFILE ".jlloc"
$pidFile = Join-Path $stateDir "daemon.pid"
$daemonPortFile = Join-Path $stateDir "daemon.port"
$metricsInfoFile = Join-Path $stateDir "metrics.info"
$daemonOutFile = Join-Path $stateDir "daemon.out.log"
$daemonErrFile = Join-Path $stateDir "daemon.err.log"
$gradlew = Join-Path $repoRoot "gradlew.bat"
$daemonExe = Join-Path $repoRoot "jlloc-daemon\build\install\jlloc-daemon\bin\jlloc-daemon.bat"
$cliExe = Join-Path $repoRoot "jlloc-cli\build\install\jlloc-cli\bin\jlloc-cli.bat"

function Ensure-Install {
    if ((Test-Path $daemonExe) -and (Test-Path $cliExe)) {
        return
    }

    Push-Location $repoRoot
    try {
        & $gradlew ":jlloc-daemon:installDist" ":jlloc-cli:installDist"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle installDist failed."
        }
    } finally {
        Pop-Location
    }
}

function Ensure-StateDir {
    if (-not (Test-Path $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir | Out-Null
    }
}

function Read-Pid {
    if (-not (Test-Path $pidFile)) {
        return $null
    }

    try {
        return [int](Get-Content $pidFile -Raw).Trim()
    } catch {
        return $null
    }
}

function Test-ProcessAlive([int]$Pid) {
    try {
        return -not (Get-Process -Id $Pid -ErrorAction Stop).HasExited
    } catch {
        return $false
    }
}

function Start-Daemon {
    Ensure-Install
    Ensure-StateDir

    $pid = Read-Pid
    if ($pid -and (Test-ProcessAlive $pid)) {
        Write-Host "jlloc daemon already running (PID $pid)"
        return 0
    }

    $startInfo = @{
        FilePath = $daemonExe
        WorkingDirectory = $repoRoot
        WindowStyle = "Hidden"
        PassThru = $true
        RedirectStandardOutput = $daemonOutFile
        RedirectStandardError = $daemonErrFile
    }
    $process = Start-Process @startInfo
    Set-Content -Path $pidFile -Value $process.Id

    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $daemonPortFile) -and (Test-Path $metricsInfoFile)) {
            break
        }
        Start-Sleep -Milliseconds 250
    }

    Write-Host "jlloc daemon started (PID $($process.Id))"
    Write-Host "  status:  jlloc status"
    Write-Host "  metrics: jlloc metrics"
    return 0
}

function Stop-Daemon {
    $pid = Read-Pid
    if (-not $pid) {
        Write-Host "jlloc daemon is not running."
        return 0
    }

    if (Test-ProcessAlive $pid) {
        Stop-Process -Id $pid -Force
    }

    Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $daemonPortFile -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $metricsInfoFile -ErrorAction SilentlyContinue

    Write-Host "jlloc daemon stopped."
    return 0
}

function Invoke-CLI([string[]]$CliArgs) {
    Ensure-Install
    Push-Location $repoRoot
    try {
        & $cliExe @CliArgs
        return $LASTEXITCODE
    } finally {
        Pop-Location
    }
}

if ($Args.Count -eq 0) {
    Write-Host "Usage: scripts\jlloc.ps1 <start|status|metrics|explain|dump|stop|help> [args]"
    Write-Host "  start    Build/install and start the daemon"
    Write-Host "  status   Show daemon + JVM status"
    Write-Host "  metrics  Show metrics endpoint health"
    Write-Host "  stop     Stop the daemon"
    exit 1
}

switch ($Args[0].ToLowerInvariant()) {
    "start" { exit (Start-Daemon) }
    "stop" { exit (Stop-Daemon) }
    "status" { exit (Invoke-CLI @("status")) }
    "metrics" { exit (Invoke-CLI @("metrics")) }
    "explain" { exit (Invoke-CLI $Args) }
    "dump" { exit (Invoke-CLI $Args) }
    "fix" { exit (Invoke-CLI $Args) }
    default {
        exit (Invoke-CLI $Args)
    }
}
