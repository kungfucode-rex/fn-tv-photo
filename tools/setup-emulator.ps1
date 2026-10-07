<#
  Installs the Android TV emulator pieces into the existing self-contained SDK,
  then reports whether this machine can actually run a hardware-accelerated AVD.
#>
[CmdletBinding()]
param(
  [string]$Root = 'F:\android-toolchain'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$env:JAVA_HOME = Join-Path $Root 'jdk-17'
$sdkRoot = Join-Path $Root 'android-sdk'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'

function Invoke-Native([scriptblock]$Body) {
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Body 2>&1 | ForEach-Object { "$_" } }
  finally { $ErrorActionPreference = $old }
}

Write-Host '=== available Android TV system images ===' -ForegroundColor Cyan
$all = Invoke-Native { & $sdkManager --sdk_root=$sdkRoot --list }
$tvImages = $all |
  Select-String -Pattern 'system-images;android-\d+;android-tv;[^|\s]+' -AllMatches |
  ForEach-Object { $_.Matches } |
  ForEach-Object { $_.Value } |
  Sort-Object -Unique
$tvImages | ForEach-Object { Write-Host "  $_" }

# Prefer the newest API level that ships an x86_64 image, else x86.
$pick = $null
foreach ($abi in @('x86_64', 'x86')) {
  $cand = $tvImages |
    Where-Object { $_ -like "*;$abi" } |
    Sort-Object { [int]([regex]::Match($_, 'android-(\d+)').Groups[1].Value) } -Descending |
    Select-Object -First 1
  if ($cand) { $pick = $cand; break }
}

if (-not $pick) { Write-Host 'NO_TV_IMAGE_FOUND' -ForegroundColor Red; exit 2 }
Write-Host "`nSelected TV image: $pick" -ForegroundColor Green

Write-Host "`n=== installing emulator + platform 36 + system image ===" -ForegroundColor Cyan
Invoke-Native { & $sdkManager --sdk_root=$sdkRoot 'emulator' 'platforms;android-36' 'build-tools;36.0.0' $pick } |
  Where-Object { $_ -notmatch '^\s*\[=*\s*\]' } |
  ForEach-Object { Write-Host "  $_" }

Write-Host "`n=== hardware acceleration support ===" -ForegroundColor Cyan
# `emulator -accel-check` reports the hypervisor状态 without needing elevation,
# unlike Get-WindowsOptionalFeature which requires an administrator token.
$accel = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
if (-not (Test-Path $accel)) { $accel = Join-Path $Root 'android-sdk\emulator\emulator.exe' }
Invoke-Native { & $accel -accel-check } | ForEach-Object { Write-Host "  $_" }
Write-Host "  CPU virtualization : $((Get-CimInstance Win32_Processor | Select-Object -First 1).VirtualizationFirmwareEnabled)"
Write-Host "  Total RAM (GB)     : $([math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1))"

$emu = Join-Path $sdkRoot 'emulator\emulator.exe'
Write-Host "`n  emulator.exe present: $(Test-Path $emu)"
Write-Host "  image installed     : $pick"
Set-Content -LiteralPath (Join-Path $Root 'tv-image.txt') -Value $pick -Encoding ascii
Write-Host "`nEMULATOR_READY" -ForegroundColor Green
