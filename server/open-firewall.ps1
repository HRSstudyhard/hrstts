#Requires -Version 5.1
<#
.SYNOPSIS
    放行「简读」电脑 TTS 服务的入站端口。

.DESCRIPTION
    手机连不上电脑，90% 是这一步没做。Windows 防火墙默认拦入站，
    而且本机的 WLAN 被识别成 **Public** 配置文件时规则更严。

    本脚本会：
      1. 自我提权（弹出 UAC）；
      2. 为当前端口创建 TCP 入站规则（所有配置文件：域/专用/公用）；
      3. 打印最终规则，便于核对。

.PARAMETER Port
    要放行的端口，默认 8080（与 server/run.py 的默认值一致）。

.PARAMETER Remove
    删除规则，而不是创建。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1
    powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1 -Port 9000
    powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1 -Remove
#>
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$ruleName = "简读 TTS 服务 ($Port)"

# ---- 自我提权 ----
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "需要管理员权限，正在重新以管理员身份启动…" -ForegroundColor Yellow
    $args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Port', $Port)
    if ($Remove) { $args += '-Remove' }
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $args | Out-Null
    } catch {
        Write-Host "提权被取消。请右键 PowerShell → 以管理员身份运行，再执行本脚本。" -ForegroundColor Red
    }
    return
}

# ---- 执行 ----
if ($Remove) {
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($existing) {
        $existing | Remove-NetFirewallRule
        Write-Host "已删除规则：$ruleName" -ForegroundColor Green
    } else {
        Write-Host "没有找到规则：$ruleName" -ForegroundColor Yellow
    }
    return
}

Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule

# -Profile Any：本机 WLAN 常被识别成 Public，只开 Private 会连不上
New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Protocol TCP `
    -LocalPort $Port `
    -Action Allow `
    -Profile Any `
    -Description "允许手机访问本机的简读 TTS 服务（局域网朗读）" | Out-Null

Write-Host ""
Write-Host "✅ 已放行 TCP $Port（配置文件：域/专用/公用）" -ForegroundColor Green
Get-NetFirewallRule -DisplayName $ruleName |
    Select-Object DisplayName, Enabled, Direction, Action, Profile |
    Format-Table -AutoSize

Write-Host "提示：若仍连不上，检查是否有第三方安全软件（360 / 火绒 / 卡巴斯基）在拦。" -ForegroundColor DarkGray
Write-Host "撤销：powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1 -Port $Port -Remove" -ForegroundColor DarkGray
