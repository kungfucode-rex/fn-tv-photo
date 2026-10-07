<#
  Finds fnOS NAS servers on the local network.

  fnOS serves its web UI and photo API on TCP 5666 (HTTP) and 5667 (HTTPS). This
  probes a /24 for those ports concurrently, which is far quicker than
  Test-NetConnection per host, and reports anything that answers.

  Usage:
    pwsh -File tools/find-nas.ps1
    pwsh -File tools/find-nas.ps1 -Subnet 192.168.1
    pwsh -File tools/find-nas.ps1 -TimeoutMs 4000
#>
[CmdletBinding()]
param(
  [string]$Subnet,
  [int[]]$Ports = @(5666, 5667),
  [int]$TimeoutMs = 2500
)

$ErrorActionPreference = 'Stop'

function Get-LocalSubnets {
  Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
      $_.IPAddress -notlike '127.*' -and
      $_.IPAddress -notlike '169.254.*' -and
      $_.PrefixLength -eq 24
    } |
    ForEach-Object { ($_.IPAddress -split '\.')[0..2] -join '.' } |
    Select-Object -Unique
}

$subnets = if ($Subnet) { @($Subnet) } else { @(Get-LocalSubnets) }
if (-not $subnets) { throw 'No /24 IPv4 interface found; pass -Subnet explicitly.' }

Write-Host "Scanning for fnOS (ports $($Ports -join ', ')) on: $($subnets -join ', ')" -ForegroundColor Cyan
Write-Host 'This only connects to TCP ports; it sends no credentials and reads no data.' -ForegroundColor DarkGray

$found = @()

foreach ($net in $subnets) {
  $clients = New-Object System.Collections.ArrayList
  $tasks = New-Object System.Collections.ArrayList

  foreach ($port in $Ports) {
    foreach ($i in 1..254) {
      $ip = "$net.$i"
      $client = New-Object System.Net.Sockets.TcpClient
      $client.NoDelay = $true
      [void]$clients.Add($client)
      try {
        $task = $client.ConnectAsync($ip, $port)
        [void]$tasks.Add([pscustomobject]@{ IP = $ip; Port = $port; Task = $task; Client = $client })
      } catch {
        # immediate failure; ignore
      }
    }
  }

  Write-Host "  probing $($tasks.Count) endpoints on $net.* ..." -ForegroundColor DarkGray

  # Deliberately not Task.WaitAll: it throws AggregateException as soon as any task
  # faults, and a refused connection *is* a fault — so it would abort on the first
  # closed port, which is almost every host.
  $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
  while ([DateTime]::UtcNow -lt $deadline) {
    $pending = @($tasks | Where-Object { -not $_.Task.IsCompleted })
    if ($pending.Count -eq 0) { break }
    Start-Sleep -Milliseconds 40
  }

  foreach ($entry in $tasks) {
    if ($entry.Task.Status -eq 'RanToCompletion' -and $entry.Client.Connected) {
      $found += [pscustomobject]@{ Address = $entry.IP; Port = $entry.Port }
    }
  }

  foreach ($c in $clients) { $c.Dispose() }
}

Write-Host "`nHosts currently in the ARP cache on these subnets:" -ForegroundColor DarkGray
$neighbours = @()
foreach ($net in $subnets) {
  $neighbours += Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -like "$net.*" -and $_.State -in @('Reachable', 'Stale', 'Permanent') }
}
if ($neighbours.Count -gt 0) {
  $neighbours | Sort-Object IPAddress | ForEach-Object { "  $($_.IPAddress)" }
} else {
  Write-Host '  (none)'
}

if ($found.Count -eq 0) {
  Write-Host "`nNo fnOS endpoint answered. Check that the NAS is powered on, on the same" -ForegroundColor Yellow
  Write-Host "network, and that its firewall allows 5666 (设置 → 安全性 → 防火墙)." -ForegroundColor Yellow
  exit 1
}

Write-Host "`nCandidate fnOS servers:" -ForegroundColor Green
foreach ($f in $found | Sort-Object Address, Port) {
  $label = if ($f.Port -eq 5666) { 'HTTP ' } else { 'HTTPS' }
  Write-Host ("  {0,-16} :{1}  ({2})" -f $f.Address, $f.Port, $label)
}
Write-Host "`nUse the address as <ip>:<port> on the login screen."
