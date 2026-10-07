<#
  Copies a freshly built APK to the share the TV is installed from, named with the
  time it was built.

  Usage:
    pwsh -File tools/publish-apk.ps1                 # release -> \\fnos-ms01\Temp\<share>\FN-tvphoto-1.2-<stamp>.apk
    pwsh -File tools/publish-apk.ps1 -Variant debug
    pwsh -File tools/publish-apk.ps1 -NoPublishCheck # skip the hash read-back

  tools/build.ps1 calls this by itself after a successful `assembleRelease` or
  `assembleDebug`, so a build and its upload are one command.

  The name carries the APK's own build time - `FN-tvphoto-1.2-20261007-145930.apk` - rather
  than a stable `FN-tvphoto-1.2.apk`, so an older build stays on the share to fall back to
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

# The release APK carries the app's name and its version rather than the Gradle
# variant's (see the androidComponents block in app/build.gradle.kts), so the version is
# not repeated here: the file is found by its prefix, and the copy on the share is named
# after whatever that file turned out to be.
$apkPatterns = @{ 'release' = 'FN-tvphoto-*.apk'; 'debug' = 'app-debug.apk' }
$pattern = $apkPatterns[$Variant]
if ([string]::IsNullOrEmpty($pattern)) { throw "publish: no APK pattern mapped for variant '$Variant'" }

$apkDir = Join-Path $projectRoot "app\build\outputs\apk\$Variant"
$matches = @(Get-ChildItem -LiteralPath $apkDir -Filter $pattern -File -ErrorAction SilentlyContinue)
if ($matches.Count -eq 0) {
  Write-Warning "publish: nothing matching $pattern in $apkDir - nothing to upload"
  exit 2
}

# A build directory can still hold the previous version's APK; the newest is the one the
# build this runs after has just written. More than one is reported rather than hidden.
$from = $matches | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($matches.Count -gt 1) {
  Write-Host ("publish: {0} APKs match {1}; taking the newest, {2}" -f $matches.Count, $pattern, $from.Name)
}

$source = $from.FullName
$stamp = $from.LastWriteTime.ToString('yyyyMMdd-HHmmss')
$base = [System.IO.Path]::GetFileNameWithoutExtension($from.Name)
$fileName = "$base-$stamp.apk"
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

  # A share can also still hold a copy published without a timestamp: this scheme's own
  # `FN-tvphoto-<version>.apk`, or an `app-<variant>.apk` left by the earlier scheme.
  # Either can only be a stale copy of one of these builds, and a folder listing with both
  # is a way to install the wrong one. Such a file is removed only when it is provably the
  # very bytes just uploaded; anything else is left alone and reported.
  foreach ($legacyName in @("$base.apk", "app-$Variant.apk")) {
    $legacy = Join-Path $Destination $legacyName
    if (Test-Path -LiteralPath $legacy) {
      $legacyHash = (Get-FileHash -LiteralPath $legacy -Algorithm SHA256).Hash
      if ($legacyHash -eq $sourceHash) {
        Remove-Item -LiteralPath $legacy -Force
        Write-Host "removed    $legacyName (same build as the copy just published)"
      } else {
        Write-Warning "publish: $legacyName on the share is a different build; left in place, consider deleting it"
      }
    }
  }

  exit 0
} catch {
  Write-Warning "publish: $($_.Exception.Message)"
  Write-Warning "publish: the APK is at $source - copy it by hand, or open the share first"
  exit 1
}
