# 把本机已下载的 Qwen3-TTS 端侧模型推送到设备应用私有目录
# 用法：powershell -File scripts\push-qwen3-model.ps1 [-Serial emulator-5554] [-Package com.hrstts.jiandu]
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.hrstts.jiandu",
    [string]$Source = "F:\Projects\hrstts\.toolchain\qwen3-models"
)

$ErrorActionPreference = "Continue"
$adb = "C:\Users\H\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d = @("-s", $Serial)

if (-not (Test-Path $Source)) { throw "模型目录不存在：$Source" }

$files = Get-ChildItem $Source -File
Write-Output ("待推送文件：" + $files.Count + " 个，合计 " + [math]::Round(($files | Measure-Object Length -Sum).Sum / 1MB, 1) + " MB")

# 先确保应用私有目录存在
& $adb @d shell run-as $Package mkdir -p files/qwen3-tts 2>$null | Out-Null

foreach ($f in $files) {
    $remote = "/data/local/tmp/" + $f.Name
    & $adb @d push $f.FullName $remote 2>$null | Out-Null
    & $adb @d shell chmod 644 $remote 2>$null | Out-Null
    & $adb @d shell run-as $Package cp $remote "files/qwen3-tts/$($f.Name)" 2>$null | Out-Null
    & $adb @d shell rm -f $remote 2>$null | Out-Null
    Write-Output ("pushed " + $f.Name)
}

& $adb @d shell am force-stop $Package 2>$null | Out-Null
Write-Output "=== 设备内文件 ==="
& $adb @d shell run-as $Package ls -l files/qwen3-tts 2>$null
