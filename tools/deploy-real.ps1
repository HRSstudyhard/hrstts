# Deploy the debug APK (and optionally the androidTest APK) to a physical device
# (vivo V2352GA / SM8635).
#
# Why this script exists: this phone's link is unreliable. `adb install`
# (streamed install) frequently dies mid-transfer with an EMPTY error message
# ("failed to install ...apk: ") and the device immediately goes `offline`.
# This script uses the non-streaming path instead -- push to /data/local/tmp,
# then `pm install -r` -- and retries across offline blips.
#
# NOTE: this file is deliberately ASCII-only. The host runs Windows PowerShell
# 5.1 with a GB2312 default encoding, which mis-decodes UTF-8 files that have no
# BOM; non-ASCII bytes inside a string literal can then terminate parsing (e.g.
# a full-width char before '<' turns it into a reserved-operator error).
#
# Usage:
#   .\tools\deploy-real.ps1                    # deploy debug APK
#   .\tools\deploy-real.ps1 -WithTest          # also install the androidTest APK
#   .\tools\deploy-real.ps1 -Serial 10.62.61.43:46559   # wireless device

[CmdletBinding()]
param(
    [string]$Serial = "10CF3T1FJQ001QF",
    [string]$Apk = "F:\Projects\hrstts\app\build\outputs\apk\debug\app-debug.apk",
    [string]$TestApk = "F:\Projects\hrstts\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk",
    [string]$Package = "com.hrstts.jiandu",
    [int]$TimeoutSec = 180,
    [switch]$Wait,
    [switch]$WithTest
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Write-Warn2($m) { Write-Host "    $m" -ForegroundColor Yellow }

# Returns: device | offline | absent
function Get-DeviceState {
    $out = & $adb devices 2>&1 | Out-String
    $line = ($out -split "`r?`n") |
        Where-Object { $_ -match [regex]::Escape($Serial) } | Select-Object -First 1
    if (-not $line) { return "absent" }
    if ($line -match "\soffline\s*$") { return "offline" }
    if ($line -match "\sdevice\s*$") { return "device" }
    return "unknown"
}

Write-Step "Waiting for device to reach 'device' state (max ${TimeoutSec}s)"
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    $state = Get-DeviceState
    if ($state -eq "device") { Write-Ok "device online"; break }
    if ($state -eq "offline") {
        Write-Warn2 "device offline - trying 'adb reconnect offline'"
        & $adb reconnect offline 2>&1 | Out-Null
    }
    if (-not $Wait -and $state -eq "absent") {
        Write-Warn2 "device absent and -Wait not given; exiting"
        exit 2
    }
    Start-Sleep -Seconds 2
}

if ((Get-DeviceState) -ne "device") {
    Write-Host "Device not ready after ${TimeoutSec}s (state: $(Get-DeviceState))" -ForegroundColor Red
    Write-Host "Check: 1) cable seated / try another cable" -ForegroundColor Red
    Write-Host "       2) phone unlocked" -ForegroundColor Red
    Write-Host "       3) 'Allow USB debugging' prompt accepted" -ForegroundColor Red
    Write-Host "       4) prefer wireless: adb pair IP:PORT then adb connect IP:PORT" -ForegroundColor Red
    exit 3
}

if (-not (Test-Path $Apk)) {
    Write-Host "APK not found: $Apk" -ForegroundColor Red
    exit 4
}

$remote = "/data/local/tmp/jiandu-deploy.apk"
$attempts = 3

# Push + `pm install -r` with retries. Returns $true on success.
# `-r` keeps app data, which is what preserves the 1.6 GB on-device model.
function Install-Apk {
    param([string]$Path, [string]$Label, [string]$RemoteName, [switch]$Fatal)

    if (-not (Test-Path $Path)) {
        Write-Host "$Label not found: $Path" -ForegroundColor Red
        if ($Fatal) { exit 4 } else { return $false }
    }

    $remotePath = "/data/local/tmp/$RemoteName"

    for ($i = 1; $i -le $attempts; $i++) {
        Write-Step "$Label attempt $i/$attempts : push"
        & $adb -s $Serial push $Path $remotePath 2>&1 | ForEach-Object { Write-Host "    $_" }
        if ($LASTEXITCODE -ne 0) {
            Write-Warn2 "push failed (device may have dropped); retrying"
            Start-Sleep -Seconds 5
            continue
        }

        Write-Step "$Label attempt $i/$attempts : pm install -r (keeps app data)"
        $out = & $adb -s $Serial shell pm install -r $remotePath 2>&1 | Out-String
        Write-Host "    $($out.Trim())"

        if ($out -match "Success") {
            Write-Ok "$Label install OK"
            & $adb -s $Serial shell rm -f $remotePath 2>&1 | Out-Null
            return $true
        }

        if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match") {
            Write-Host "Signature mismatch: installed app was signed with a different key." -ForegroundColor Red
            Write-Host "Uninstall first (WARNING: deletes app data AND the 1.6 GB model):" -ForegroundColor Red
            Write-Host "  & `"$adb`" -s $Serial uninstall $Package" -ForegroundColor Red
            exit 5
        }

        Write-Warn2 "install not successful; retrying in 5s"
        Start-Sleep -Seconds 5
    }
    return $false
}

if (-not (Install-Apk -Path $Apk -Label "app" -RemoteName "jiandu-deploy.apk" -Fatal)) {
    Write-Host "Failed to install the app after $attempts attempts." -ForegroundColor Red
    exit 6
}

Write-Step "Verify installed version"
& $adb -s $Serial shell dumpsys package $Package 2>&1 |
    Select-String "versionCode|versionName" | Select-Object -First 2 |
    ForEach-Object { Write-Host "    $($_.ToString().Trim())" }

Write-Step "Verify the 1.6 GB model survived (expect 11 files)"
$model = & $adb -s $Serial shell run-as $Package ls /data/data/$Package/files/qwen3-tts 2>&1
$count = ($model | Where-Object { $_ -match "\.(tflite|npy|npz|json|txt)$" }).Count
Write-Host "    model files: $count"
if ($count -lt 11) {
    Write-Warn2 "fewer than 11 model files - on-device engine may be unusable"
}

if ($WithTest) {
    if (-not (Install-Apk -Path $TestApk -Label "androidTest" -RemoteName "jiandu-test.apk")) {
        Write-Host "Failed to install the androidTest APK." -ForegroundColor Red
        exit 7
    }
    Write-Step "Verify test package is registered"
    & $adb -s $Serial shell pm list packages 2>&1 |
        Select-String ([regex]::Escape("$Package.test")) |
        ForEach-Object { Write-Host "    $($_.ToString().Trim())" }
}

exit 0
