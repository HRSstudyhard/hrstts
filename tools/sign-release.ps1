# Sign the release APK with the standard Android debug key (for local testing only).
#
# Why: `gradlew :app:assembleRelease` produces app-release-unsigned.apk, and Android
# refuses to install unsigned APKs (INSTALL_PARSE_FAILED_NO_CERTIFICATES).
# This signs a copy so the R8-minified build can actually be installed and verified.
#
# WARNING: this is NOT a release signing key. Provide a real keystore before distributing
# (see docs/DEV.md section 7).
#
# Usage:
#   .\tools\sign-release.ps1

param(
    [string]$Apk = "app\build\outputs\apk\release\app-release-unsigned.apk",
    [string]$Out = "app\build\outputs\apk\release\app-release-debug-signed.apk",
    [string]$Keystore = "$env:USERPROFILE\.android\debug.keystore"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = Join-Path $repo ".toolchain\jdk-21"
if (-not (Test-Path $env:JAVA_HOME)) { throw "Project JDK not found: $env:JAVA_HOME" }

$buildTools = Get-ChildItem "C:\Users\H\AppData\Local\Android\Sdk\build-tools" -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
if (-not (Test-Path $apksigner)) { throw "apksigner not found: $apksigner" }
if (-not (Test-Path $Keystore)) { throw "Debug keystore not found: $Keystore" }

$apkPath = Join-Path $repo $Apk
$outPath = Join-Path $repo $Out
if (-not (Test-Path $apkPath)) { throw "APK not found: $apkPath (run gradlew :app:assembleRelease first)" }

& $apksigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $outPath $apkPath

& $apksigner verify --print-certs $outPath | Select-Object -First 3
Write-Output "signed output: $outPath"
