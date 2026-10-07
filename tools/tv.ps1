<#
  Small helper for driving the Android TV emulator.

  Usage:
    pwsh -File tools/tv.ps1 install
    pwsh -File tools/tv.ps1 launch
    pwsh -File tools/tv.ps1 shot login
    pwsh -File tools/tv.ps1 key DPAD_DOWN DPAD_CENTER
    pwsh -File tools/tv.ps1 text "10.0.2.2"
    pwsh -File tools/tv.ps1 status
    pwsh -File tools/tv.ps1 logcat
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)][string]$Action = 'status',
  [Parameter(Position = 1)][string[]]$Rest = @(),
  [string]$ToolchainRoot = 'F:\android-toolchain',
  # The packaged id, not the code namespace: applicationId carries the author's own
  # prefix while the classes stay under com.tvphoto (see app/build.gradle.kts).
  [string]$AppId = 'com.kungfucode.fntvphoto.debug',
  [string]$Activity = 'com.tvphoto.MainActivity'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $ToolchainRoot 'android-sdk\platform-tools\adb.exe'
$shotDir = Join-Path $projectRoot 'artifacts\screenshots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

function Invoke-Adb([string[]]$AdbArgs, [switch]$Quiet) {
  # adb writes progress to stderr, which PowerShell 5.1 promotes to a terminating
  # error while $ErrorActionPreference is 'Stop'.
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $adb @AdbArgs 2>&1
    if (-not $Quiet) { $out | ForEach-Object { "$_" } }
    return $out
  } finally { $ErrorActionPreference = $old }
}

switch ($Action) {
  'install' {
    $variant = if ($Rest.Count -gt 0) { $Rest[0] } else { 'debug' }
    # The same files the build writes and tools/publish-apk.ps1 uploads: the release APK
    # is named after the app and its version, the debug one after the Gradle variant (see
    # the androidComponents block in app/build.gradle.kts). The version is not repeated
    # here, so the newest match is the one that was just built.
    $apkPatterns = @{ 'release' = 'FN-tvphoto-*.apk'; 'debug' = 'app-debug.apk' }
    $apkDir = Join-Path $projectRoot "app\build\outputs\apk\$variant"
    $apk = Get-ChildItem -LiteralPath $apkDir -Filter $apkPatterns[$variant] -File -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "APK not found: nothing matching $($apkPatterns[$variant]) in $apkDir (run tools/build.ps1 -Tasks assemble$($variant.Substring(0,1).ToUpper()+$variant.Substring(1)) first)" }
    Invoke-Adb @('install', '-r', '-g', $apk.FullName) | Out-Null
    Write-Host "installed $variant -> $($apk.FullName)"
  }

  'launch' {
    Invoke-Adb @('shell', 'am', 'start', '-n', "$AppId/$Activity") | Out-Null
    Start-Sleep -Seconds 3
    Write-Host 'launched'
  }

  'restart' {
    Invoke-Adb @('shell', 'am', 'force-stop', $AppId) | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb @('shell', 'am', 'start', '-n', "$AppId/$Activity") | Out-Null
    Start-Sleep -Seconds 3
    Write-Host 'restarted'
  }

  'shot' {
    $name = if ($Rest.Count -gt 0) { $Rest[0] } else { 'shot' }
    $remote = "/sdcard/$name.png"
    $local = Join-Path $shotDir "$name.png"
    Invoke-Adb @('shell', 'rm', '-f', $remote) | Out-Null
    Invoke-Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-Adb @('pull', $remote, $local) | Out-Null
    Write-Host "screenshot -> $local"
  }

  'key' {
    foreach ($k in $Rest) {
      Invoke-Adb @('shell', 'input', 'keyevent', $k) | Out-Null
      Start-Sleep -Milliseconds 400
    }
    Write-Host "sent: $($Rest -join ' ')"
  }

  'text' {
    $value = $Rest -join ' '
    # `input text` treats spaces specially; %s is its escape for a space.
    $escaped = $value -replace ' ', '%s'
    Invoke-Adb @('shell', 'input', 'text', $escaped) | Out-Null
    Write-Host "typed: $value"
  }

  'logcat' {
    Invoke-Adb (@('logcat', '-d', '-v', 'brief') + $Rest) | Out-Null
  }

  'logcat-clear' { Invoke-Adb @('logcat', '-c') | Out-Null; Write-Host 'logcat cleared' }

  'crash' {
    Invoke-Adb @('logcat', '-d', '-v', 'brief', '*:E') | Out-Null
  }

  'status' {
    Invoke-Adb @('devices', '-l')
    Write-Host ''
    $pkg = Invoke-Adb @('shell', 'pm', 'list', 'packages', $AppId)
    Write-Host "installed: $pkg"
  }

  default { throw "Unknown action '$Action'" }
}
