<#
  Copies a freshly built APK to the share the TV is installed from, named with the
  time it was built.

  Usage:
    pwsh -File tools/publish-apk.ps1                 # release -> \\fnos-ms01\Temp\<share>\app-release-<stamp>.apk
    pwsh -File tools/publish-apk.ps1 -Variant debug
    pwsh -File tools/publish-apk.ps1 -NoPublishCheck # skip the hash read-back

  tools/build.ps1 calls this by itself after a successful `assembleRelease` or
  `assembleDebug`, so a build and its upload are one command.

  The name carries the APK's own build time - `app-release-20261007-145930.apk` - rather
  than a stable `app-release.apk`, so an older build stays on the share to fall back to
  and nothing is silently overwritten. It is taken from the file's timestamp, not from
  the clock at upload, which makes republishing the same APK produce the same name
  instead of a duplicate.

  A share that is asleep, offline or not authenticated must not fail a build that has
  already produced its APK, so every failure here is reported and returned as a
  non-zero exit code rather than thrown.

  This file is deliberately pure ASCII. Windows PowerShell decodes a .ps1 as ANSI unless
  it carries a UTF-8 BOM, so any non-ASCII character in it - a comment, a path, a dash in
  a message - is silently mangled before the script even runs. The share name is
  therefore assembled from code points below, and no message here uses a character
  outside ASCII.
#>
[CmdletBinding()]
param(
  [ValidateSet('release', 'debug')][string]$Variant = 'release',
  [string]$Destination = '',
  [string]$ToolchainRoot = 'F:\android-toolchain',
  [switch]$NoPublishCheck
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# The share is \\fnos-ms01\Temp\ followed by the two Chinese characters for "software"
# (U+8F6F, U+4EF6). Spelled in code points for the reason above.
$defaultDestination = '\\fnos-ms01\Temp\' + [char]0x8F6F + [char]0x4EF6
if ([string]::IsNullOrWhiteSpace($Destination)) { $Destination = $defaultDestination }

$source = Join-Path $projectRoot "app\build\outputs\apk\$Variant\app-$Variant.apk"
if (-not (Test-Path $source)) {
  Write-Warning "publish: no APK at $source - nothing to upload"
  exit 2
}

$from = Get-Item -LiteralPath $source
$stamp = $from.LastWriteTime.ToString('yyyyMMdd-HHmmss')
$fileName = "app-$Variant-$stamp.apk"
$target = Join-Path $Destination $fileName

try {
  if (-not (Test-Path -LiteralPath $Destination)) {
    throw "share is not reachable: $Destination"
  }

  Copy-Item -LiteralPath $source -Destination $target -Force

  $to = Get-Item -LiteralPath $target
  if ($from.Length -ne $to.Length) {
    throw "size mismatch after copy: $($from.Length) -> $($to.Length)"
  }

  # The point of this step is that what the TV can install is the build just made, so
  # the copy is read back rather than assumed.
  $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
  if (-not $NoPublishCheck) {
    $targetHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($sourceHash -ne $targetHash) {
      throw "hash mismatch after copy: $sourceHash -> $targetHash"
    }
  }

  Write-Host ("published  {0}  {1:N1} MB  ->  {2}" -f $fileName, ($to.Length / 1MB), $target)

  # Earlier builds were published under the plain `app-<variant>.apk`. Once names carry
  # a timestamp, that file can only be a stale copy of one of these, and a listing with
  # both is a way to install the wrong one. It is removed only when it is provably the
  # very bytes just uploaded; anything else is left alone and reported.
  $legacy = Join-Path $Destination "app-$Variant.apk"
  if (Test-Path -LiteralPath $legacy) {
    $legacyHash = (Get-FileHash -LiteralPath $legacy -Algorithm SHA256).Hash
    if ($legacyHash -eq $sourceHash) {
      Remove-Item -LiteralPath $legacy -Force
      Write-Host "removed    app-$Variant.apk (same build as the copy just published)"
    } else {
      Write-Warning "publish: app-$Variant.apk on the share is a different build; left in place, consider deleting it"
    }
  }

  exit 0
} catch {
  Write-Warning "publish: $($_.Exception.Message)"
  Write-Warning "publish: the APK is at $source - copy it by hand, or open the share first"
  exit 1
}
