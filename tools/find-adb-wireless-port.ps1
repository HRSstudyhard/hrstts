# Locate the adb wireless-debugging TCP port on a phone whose USB link is dead.
#
# Rationale: `adb pair` uses a short-lived PAIRING port; the persistent DEBUG port
# (shown on the Wireless-debugging screen, e.g. 10.62.61.43:37451) is a different
# number and is NOT exposed to us. mDNS discovery usually finds it, but Windows
# firewall commonly blocks adb's mDNS, so we fall back to probing.
#
# The probe deliberately stays inside Android's typical wireless-debug range and
# uses a short timeout, so it is a few thousand quick TCP connects at most.
#
# ASCII-only (host is Windows PowerShell 5.1 with a GB2312 default encoding).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [int]$From = 30000,
    [int]$To = 49999,
    [int]$TimeoutMs = 100,
    [int]$Throttle = 256
)

$ErrorActionPreference = "Continue"

Write-Host "==> Probing $Ip ports $From..$To (timeout ${TimeoutMs}ms, $Throttle workers)"

$sw = [System.Diagnostics.Stopwatch]::StartNew()

# Build the scriptblock once; each runspace takes a slice of the port list.
$worker = {
    param($ip, $ports, $timeoutMs)
    $open = New-Object System.Collections.ArrayList
    foreach ($p in $ports) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $iar = $client.BeginConnect($ip, $p, $null, $null)
            $ok = $iar.AsyncWaitHandle.WaitOne($timeoutMs, $false)
            if ($ok -and $client.Connected) { [void]$open.Add($p) }
        } catch {
            # connection refused / timeout -> not the port we want
        } finally {
            try { $client.Close() } catch { }
        }
    }
    return $open
}

$all = $From..$To
$chunkSize = [math]::Ceiling($all.Count / $Throttle)
$slices = @()
for ($i = 0; $i -lt $all.Count; $i += $chunkSize) {
    $end = [math]::Min($i + $chunkSize - 1, $all.Count - 1)
    $slices += , ($all[$i..$end])
}

$pool = [runspacefactory]::CreateRunspacePool(1, $Throttle)
$pool.Open()
$handles = @()
foreach ($slice in $slices) {
    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    [void]$ps.AddScript($worker).AddArgument($Ip).AddArgument($slice).AddArgument($TimeoutMs)
    $handles += [PSCustomObject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}

$open = New-Object System.Collections.ArrayList
foreach ($h in $handles) {
    try {
        $out = $h.PS.EndInvoke($h.Handle)
        foreach ($p in $out) { [void]$open.Add([int]$p) }
    } catch { }
    $h.PS.Dispose()
}
$pool.Close()
$pool.Dispose()

$sw.Stop()
Write-Host "==> Finished in $([math]::Round($sw.Elapsed.TotalSeconds,1))s"

$open = $open | Sort-Object -Unique
if ($open.Count -eq 0) {
    Write-Host "No open port found in $From..$To." -ForegroundColor Yellow
    Write-Host "Most likely the Wireless-debugging screen is closed (it stops the listener)." -ForegroundColor Yellow
    Write-Host "Open Settings > Developer options > Wireless debugging and keep it on screen," -ForegroundColor Yellow
    Write-Host "then read the 'IP address & Port' line and pass that port directly." -ForegroundColor Yellow
    exit 2
}

Write-Host "Open ports: $($open -join ', ')" -ForegroundColor Green
foreach ($p in $open) {
    Write-Host "==> trying adb connect ${Ip}:$p"
    $out = & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect "${Ip}:$p" 2>&1 | Out-String
    Write-Host "    $($out.Trim())"
    if ($out -match "connected to") { Write-Host "SUCCESS: ${Ip}:$p" -ForegroundColor Green }
}
