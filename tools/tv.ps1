<#
  Small helper for driving the Android TV emulator.

  Usage:
    pwsh -File tools/tv.ps1 install
    pwsh -File tools/tv.ps1 launch
    pwsh -File tools/tv.ps1 shot login      # -> artifacts/screenshots/login.jpg (JPEG, not PNG)
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

function Convert-ScreenshotToJpeg([string]$PngPath, [int]$Quality = 88) {
  # adb can only screencap PNG, and a PNG of a photo app runs 1-3 MB per frame — the
  # collection reached 119 MB before this. JPEG q88 is indistinguishable for these and
  # about a fifth of the size. .NET rather than an image tool, so the helper keeps no
  # dependency beyond Windows itself. Returns the .jpg path, or $null if it could not
  # convert (in which case the PNG is left alone rather than lost).
  try {
    Add-Type -AssemblyName System.Drawing
    $image = [System.Drawing.Image]::FromFile($PngPath)
    try {
      $encoder = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
      $parameters = [System.Drawing.Imaging.EncoderParameters]::new(1)
      $parameters.Param[0] = [System.Drawing.Imaging.EncoderParameter]::new(
        [System.Drawing.Imaging.Encoder]::Quality, [int64]$Quality)
      $jpg = [IO.Path]::ChangeExtension($PngPath, '.jpg')
      $image.Save($jpg, $encoder, $parameters)
      return $jpg
    } finally { $image.Dispose() }
  } catch {
    Write-Host "  (JPEG conversion failed: $($_.Exception.Message))"
    return $null
  }
}

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
    $png = Join-Path $shotDir "$name.png"
    Invoke-Adb @('shell', 'rm', '-f', $remote) | Out-Null
    Invoke-Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-Adb @('pull', $remote, $png) | Out-Null
    $jpg = Convert-ScreenshotToJpeg $png
    if ($jpg) {
      Remove-Item -LiteralPath $png -Force
      Write-Host "screenshot -> $jpg"
    } else {
      Write-Host "screenshot -> $png"
    }
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
