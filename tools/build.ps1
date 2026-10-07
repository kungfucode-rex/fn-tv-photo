<#
  Runs Gradle for this project with the self-contained toolchain.

  Usage:
    pwsh -File tools/build.ps1 assembleDebug
    pwsh -File tools/build.ps1 assembleRelease
    pwsh -File tools/build.ps1 clean

  A run that assembles an APK also uploads it to the share the TV is installed from
  (see tools/publish-apk.ps1) - building and publishing are one step on purpose, so
  the file on the NAS cannot lag behind the source. Release is what gets published by
  default: the debug APK is for the emulator, and pushing it too would only leave a
  second file in a folder people install from. Pass -NoPublish to build only,
  -PublishVariants @('release','debug') to send both, or -PublishDestination to point
  somewhere else.

  Like tools/publish-apk.ps1, this file is deliberately pure ASCII: Windows PowerShell
  decodes a .ps1 as ANSI unless it carries a UTF-8 BOM.
#>
[CmdletBinding()]
param(
  [string[]]$Tasks = @('assembleDebug'),
  [string[]]$GradleArgs = @(),
  [string]$ToolchainRoot = 'F:\android-toolchain',
  [ValidateSet('release', 'debug')][string[]]$PublishVariants = @('release'),
  # Left empty so tools/publish-apk.ps1 supplies the share: it can spell the name in
  # code points, which a literal here cannot survive (see the note in that script).
  [string]$PublishDestination = '',
  [switch]$NoPublish
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME = Join-Path $ToolchainRoot 'jdk-17'
$env:ANDROID_HOME = Join-Path $ToolchainRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

$gradle = Join-Path $ToolchainRoot 'gradle-8.14.5\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "Gradle not found at $gradle" }

# Which variants this run is asked to package, so publishing only happens for an APK
# that was actually rebuilt and that somebody asked to share. `clean` and the test
# tasks package nothing.
$variants = @()
foreach ($task in $Tasks) {
  if ($task -match '^assemble(Release|Debug)$') {
    $variant = $Matches[1].ToLowerInvariant()
    if ($PublishVariants -contains $variant) { $variants += $variant }
  }
}

Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"
Write-Host "Tasks        = $($Tasks -join ' ')"
Write-Host ''

& $gradle --project-dir $projectRoot --console=plain --stacktrace @Tasks @GradleArgs
$result = $LASTEXITCODE

if ($result -eq 0 -and -not $NoPublish) {
  foreach ($variant in ($variants | Select-Object -Unique)) {
    $publishArgs = @{ Variant = $variant }
    if (-not [string]::IsNullOrWhiteSpace($PublishDestination)) {
      $publishArgs.Destination = $PublishDestination
    }
    & (Join-Path $PSScriptRoot 'publish-apk.ps1') @publishArgs
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "build: the $variant APK was built but not published"
    }
  }
}

exit $result
