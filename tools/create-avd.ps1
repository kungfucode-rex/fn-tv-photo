<#
  Creates (idempotently) an Android TV AVD backed by the installed TV system image.
#>
[CmdletBinding()]
param(
  [string]$Root = 'F:\android-toolchain',
  [string]$Name = 'tvphoto_tv',
  [string]$Device = 'tv_1080p',
  [string]$Image = 'system-images;android-36;android-tv;x86_64'
)

$ErrorActionPreference = 'Stop'
$sdkRoot = Join-Path $Root 'android-sdk'
$env:JAVA_HOME = Join-Path $Root 'jdk-17'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

$avdManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$imageFile = Join-Path $Root 'tv-image.txt'
$image = if (Test-Path $imageFile) { (Get-Content $imageFile -Raw).Trim() } else { $Image }
if (-not $image) { $image = $Image }

$old = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

$existing = & $avdManager list avd 2>&1 | Out-String
$ErrorActionPreference = $old

if ($existing -match "Name:\s+$([regex]::Escape($Name))\b") {
  Write-Host "AVD '$Name' already exists." -ForegroundColor Green
} else {
  Write-Host "Creating AVD '$Name' from $image ($Device)..." -ForegroundColor Cyan
  $ErrorActionPreference = 'Continue'
  'no' | & $avdManager create avd --name $Name --package $image --device $Device --force 2>&1 |
    ForEach-Object { Write-Host "  $_" }
  $ErrorActionPreference = $old
}

# TV AVDs default to a small data partition; bump it and lock the locale so the
# emulator boots straight into a usable state.
$avdIni = Join-Path $env:USERPROFILE ".android\avd\$Name.avd\config.ini"
if (Test-Path $avdIni) {
  $cfg = Get-Content $avdIni
  $set = {
    param($key, $value)
    if ($cfg -match "(?m)^$([regex]::Escape($key))=") {
      $script:cfg = $cfg -replace "(?m)^$([regex]::Escape($key))=.*$", "$key=$value"
    } else {
      $script:cfg += "$key=$value"
    }
  }
  & $set 'disk.dataPartition.size' '4096M'
  & $set 'hw.ramSize' '2048'
  & $set 'hw.keyboard' 'yes'
  Set-Content -LiteralPath $avdIni -Value $script:cfg -Encoding ascii
  Write-Host "Tuned $avdIni" -ForegroundColor Green
}

Write-Host "AVD_READY $Name" -ForegroundColor Green
