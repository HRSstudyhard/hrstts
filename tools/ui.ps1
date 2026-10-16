# docs/scripts: 模拟器 UI 自动化小工具（P5 回归用）
#
# 用法：
#   .\tools\ui.ps1 dump                     # 输出当前界面的可点击节点（含文本）
#   .\tools\ui.ps1 tap <x> <y>              # 点击坐标
#   .\tools\ui.ps1 tapText "文本"           # 按文本（或 content-desc）点击
#   .\tools\ui.ps1 shot <文件名>            # 截图到 docs/shots/
#   .\tools\ui.ps1 back                     # 返回键
#
# 说明：坐标来自 uiautomator dump 的真实 bounds，避免手写坐标猜错。

param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Action,
    [Parameter(Position = 1)][string]$A,
    [Parameter(Position = 2)][string]$B
)

$ErrorActionPreference = "Stop"
$adb = "C:\Users\H\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$repo = Split-Path -Parent $PSScriptRoot

function Get-UiXml {
    & $adb shell uiautomator dump /sdcard/_ui.xml | Out-Null
    $tmp = Join-Path $env:TEMP "_ui_$(Get-Random).xml"
    & $adb pull /sdcard/_ui.xml $tmp | Out-Null
    return [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
}

function Get-Nodes([string]$xml) {
    $result = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $n = $m.Value
        $text = [regex]::Match($n, 'text="([^"]*)"').Groups[1].Value
        $desc = [regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value
        $cls = [regex]::Match($n, 'class="([^"]*)"').Groups[1].Value
        $click = [regex]::Match($n, 'clickable="([^"]*)"').Groups[1].Value
        $bm = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bm.Success) { continue }
        $x1 = [int]$bm.Groups[1].Value; $y1 = [int]$bm.Groups[2].Value
        $x2 = [int]$bm.Groups[3].Value; $y2 = [int]$bm.Groups[4].Value
        $result += [pscustomobject]@{
            text = $text; desc = $desc; cls = $cls; clickable = $click
            x1 = $x1; y1 = $y1; x2 = $x2; y2 = $y2
            cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2)
        }
    }
    return $result
}

switch ($Action) {
    "dump" {
        Get-Nodes (Get-UiXml) | Where-Object { $_.text -ne "" -or $_.desc -ne "" -or $_.clickable -eq "true" } |
            ForEach-Object { "{0,4},{1,4}  clickable={2,-5} text='{3}' desc='{4}'" -f $_.cx, $_.cy, $_.clickable, $_.text, $_.desc }
    }
    "tap" {
        & $adb shell input tap ([int]$A) ([int]$B) | Out-Null
    }
    "tapText" {
        $nodes = Get-Nodes (Get-UiXml)
        $hit = $nodes | Where-Object { $_.text -eq $A -or $_.desc -eq $A } | Select-Object -First 1
        if (-not $hit) {
            $hit = $nodes | Where-Object { $_.text -like "*$A*" -or $_.desc -like "*$A*" } | Select-Object -First 1
        }
        if (-not $hit) { Write-Error "未找到文本/描述：$A"; exit 2 }
        & $adb shell input tap $hit.cx $hit.cy | Out-Null
        Write-Output ("tapped '{0}' at {1},{2}" -f $A, $hit.cx, $hit.cy)
    }
    "shot" {
        $target = Join-Path $repo "docs\shots\$A"
        & $adb shell screencap -p /sdcard/_shot.png | Out-Null
        & $adb pull /sdcard/_shot.png $target | Out-Null
        Write-Output "saved $target"
    }
    "back" {
        & $adb shell input keyevent 4 | Out-Null
    }
    default { Write-Error "未知动作：$Action" }
}
