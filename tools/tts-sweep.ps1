# Parameter sweep driver for on-device Qwen3-TTS synthesis (plan C + E).
#
# Runs TtsBenchmarkTest once per configuration and collects the structured
# TTSBENCH lines from logcat into a single comparison table.
#
# ASCII-only on purpose (host is Windows PowerShell 5.1 / GB2312 default; a
# UTF-8 file without BOM containing non-ASCII inside quotes can fail to parse).
#
# Usage:
#   .\tools\tts-sweep.ps1 -Serial 10.62.61.43:37451
#   .\tools\tts-sweep.ps1 -Serial <s> -Preset chunk
#   .\tools\tts-sweep.ps1 -Serial <s> -Preset quick     # fast smoke of both axes
#
# Presets:
#   chunk  - CHUNK_FRAMES 8/16/24/32/48 at default threads   (plan C)
#   thread - global threads 2/4/6/8 at default chunk         (plan E)
#   fp16   - codec FORCE_FP16 on/off                          (plan E)
#   quick  - a small subset of both axes

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet("chunk", "thread", "fp16", "quick")][string]$Preset = "quick",
    [string]$Package = "com.hrstts.jiandu",
    [string]$Runner = "com.hrstts.jiandu.test/androidx.test.runner.AndroidJUnitRunner",
    [string]$TestClass = "com.hrstts.jiandu.TtsBenchmarkTest",
    [int]$SettleSec = 8,
    [string]$OutFile = ""
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Write-Warn2($m) { Write-Host "    $m" -ForegroundColor Yellow }

# NOTE: the parameter must NOT be called $Args / $Input / $Error etc. --
# PowerShell variable names are case-insensitive, so $Args collides with the
# automatic $args variable and the argument array is silently lost.
function Invoke-Adb {
    param([string[]]$AdbArgs)
    return (& $adb -s $Serial @AdbArgs 2>&1 | Out-String)
}

# Config = hashtable of setprop name -> value ("" means clear the property)
$configs = switch ($Preset) {
    "chunk" {
        @(
            @{ Name = "chunk8";  Props = @{ "debug.jiandu.chunkFrames" = "8" } },
            @{ Name = "chunk16"; Props = @{ "debug.jiandu.chunkFrames" = "16" } },
            @{ Name = "chunk24"; Props = @{ "debug.jiandu.chunkFrames" = "24" } },
            @{ Name = "chunk32"; Props = @{ "debug.jiandu.chunkFrames" = "32" } },
            @{ Name = "chunk48"; Props = @{ "debug.jiandu.chunkFrames" = "48" } }
        )
    }
    "thread" {
        @(
            @{ Name = "threads2"; Props = @{ "debug.jiandu.threads" = "2" } },
            @{ Name = "threads4"; Props = @{ "debug.jiandu.threads" = "4" } },
            @{ Name = "threads6"; Props = @{ "debug.jiandu.threads" = "6" } },
            @{ Name = "threads8"; Props = @{ "debug.jiandu.threads" = "8" } }
        )
    }
    "fp16" {
        @(
            @{ Name = "fp16_on";  Props = @{ "debug.jiandu.codecFp16" = "1" } },
            @{ Name = "fp16_off"; Props = @{ "debug.jiandu.codecFp16" = "0" } }
        )
    }
    "quick" {
        @(
            @{ Name = "default";  Props = @{ "debug.jiandu.tuning" = "0" } },
            @{ Name = "chunk32";  Props = @{ "debug.jiandu.tuning" = "1"; "debug.jiandu.chunkFrames" = "32" } },
            @{ Name = "threads8"; Props = @{ "debug.jiandu.tuning" = "1"; "debug.jiandu.threads" = "8" } }
        )
    }
}

Write-Step "Verifying device $Serial"
if ((Invoke-Adb @("shell", "echo", "ok")) -notmatch "ok") {
    Write-Host "Device not reachable: $Serial" -ForegroundColor Red
    exit 2
}

Write-Step "Verifying test package is installed"
$pkgs = Invoke-Adb @("shell", "pm", "list", "packages")
if ($pkgs -notmatch [regex]::Escape("$Package.test")) {
    Write-Host "Test APK not installed. Run:" -ForegroundColor Red
    Write-Host "  .\gradlew.bat :app:assembleDebugAndroidTest" -ForegroundColor Red
    Write-Host "  & `"$adb`" -s $Serial install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" -ForegroundColor Red
    exit 3
}

$allResults = @()
$resultsDir = Join-Path (Get-Location) "docs\perf"
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir -Force | Out-Null }
if (-not $OutFile) {
    $OutFile = Join-Path $resultsDir ("sweep-{0}-{1}.txt" -f $Preset, (Get-Date -Format "yyyyMMdd-HHmmss"))
}

foreach ($cfg in $configs) {
    Write-Step "config '$($cfg.Name)'"

    # Reset every knob, then apply this config. Properties are frozen at first read
    # in-process, so the force-stop below is what actually makes them take effect.
    Invoke-Adb @("shell", "setprop", "debug.jiandu.tuning", "1") | Out-Null
    foreach ($k in @("debug.jiandu.chunkFrames", "debug.jiandu.threads", "debug.jiandu.talkerThreads",
                     "debug.jiandu.mtpThreads", "debug.jiandu.codecThreads", "debug.jiandu.codecFp16")) {
        Invoke-Adb @("shell", "setprop", $k, '""') | Out-Null
    }
    foreach ($k in $cfg.Props.Keys) {
        Invoke-Adb @("shell", "setprop", $k, $cfg.Props[$k]) | Out-Null
    }
    # read-back so the transcript proves what was actually set
    $seen = @()
    foreach ($k in $cfg.Props.Keys) {
        $v = (Invoke-Adb @("shell", "getprop", $k)).Trim()
        $seen += "$k=$v"
    }
    Write-Host "    props: $($seen -join ' ')"

    Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
    Invoke-Adb @("logcat", "-c") | Out-Null

    Write-Host "    running benchmark (this takes minutes - model load + N syntheses)..."
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $run = Invoke-Adb @("shell", "am", "instrument", "-w",
        "-e", "bench", "true",
        "-e", "class", $TestClass,
        $Runner)
    $sw.Stop()

    if ($run -notmatch "OK \(|FAILURES") {
        Write-Warn2 "instrument did not report OK; tail:"
        ($run -split "`r?`n" | Select-Object -Last 6) | ForEach-Object { Write-Host "      $_" }
    }

    $bench = Invoke-Adb @("logcat", "-d", "-s", "TTSBENCH:I")
    $lines = ($bench -split "`r?`n") | Where-Object { $_ -match "TTSBENCH" }
    foreach ($l in $lines) { Write-Host "      $l" }

    $allResults += [PSCustomObject]@{
        Config = $cfg.Name
        Props  = ($seen -join ' ')
        WallSec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        Lines  = $lines
    }

    Write-Ok "settling ${SettleSec}s before next config"
    Start-Sleep -Seconds $SettleSec
}

# Restore defaults so a later manual run is not silently left on an experiment
Invoke-Adb @("shell", "setprop", "debug.jiandu.tuning", "0") | Out-Null
Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null

Write-Step "Writing transcript to $OutFile"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# TTS synthesis sweep - preset=$Preset device=$Serial")
[void]$sb.AppendLine("# date=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void]$sb.AppendLine("")
foreach ($r in $allResults) {
    [void]$sb.AppendLine("## $($r.Config)   (props: $($r.Props); wall=$($r.WallSec)s)")
    foreach ($l in $r.Lines) { [void]$sb.AppendLine($l) }
    [void]$sb.AppendLine("")
}
# UTF-8 with BOM so Windows tooling and editors read it consistently
[System.IO.File]::WriteAllText($OutFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($true)))
Write-Ok "done"

Write-Host ""
Write-Host "Next: compare the 'summary sample=... bestRtf=' lines across configs." -ForegroundColor Cyan
