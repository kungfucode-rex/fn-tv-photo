<#
  Writes version.json for a release build - the manifest the app's "check for update"
  row reads to decide whether a newer build exists and to verify the APK it downloads.

  Usage:
    pwsh -File tools/release-manifest.ps1
    pwsh -File tools/release-manifest.ps1 -Repo kungfucode-rex/fn-tv-photo

  Output:
    app/build/outputs/apk/release/version.json

  Attach that file to the same GitHub release as the APK, under exactly the name
  version.json. The app asks for it at

    https://github.com/<repo>/releases/latest/download/version.json

  which GitHub always resolves against the newest published release, so the URL in the
  app never changes and the manifest can never describe a different build than the APK
  sitting beside it. A release with no version.json is not an error: the app falls back
  to reading the tag alone and then only reports the version - it will not download a
  file it cannot verify.

  Version and version code are read out of app/build.gradle.kts rather than repeated
  here, because that file already holds the single spelling of the version (see the note
  at the top of it). tools/build.ps1 runs this after a successful assembleRelease, so a
  build that is meant to be shipped leaves its manifest beside the APK.

  Like the other scripts in this directory, this file is deliberately pure ASCII:
  Windows PowerShell decodes a .ps1 as ANSI unless it carries a UTF-8 BOM.
#>
[CmdletBinding()]
param(
  # Which site's attachment URL to write. The app reads a manifest from each site it
  # knows about - Gitee for domestic networks, GitHub for everywhere else - and the two
  # differ in exactly this: where the APK can be downloaded from.
  [string]$Site = 'https://github.com',
  [string]$Repo = 'kungfucode-rex/fn-tv-photo',
  [string]$Tag = '',
  [string]$ApkDir = '',
  [string]$ProjectRoot = '',
  # Defaults to version.json beside the APK; the second site's manifest needs its own
  # path so the first one is not overwritten by the second call.
  [string]$OutFile = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
  $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
if ([string]::IsNullOrWhiteSpace($ApkDir)) {
  $ApkDir = Join-Path $ProjectRoot 'app\build\outputs\apk\release'
}

$buildFile = Join-Path $ProjectRoot 'app\build.gradle.kts'
if (-not (Test-Path -LiteralPath $buildFile)) { throw "no build file at $buildFile" }
$buildText = Get-Content -LiteralPath $buildFile -Raw

$nameMatch = [regex]::Match($buildText, 'val\s+appVersionName\s*=\s*"([^"]+)"')
if (-not $nameMatch.Success) { throw 'appVersionName not found in app/build.gradle.kts' }
$versionName = $nameMatch.Groups[1].Value

# versionCode is an assignment inside defaultConfig, and the comment above it may hold
# another number, so the match is anchored on the whole line.
$codeMatch = [regex]::Match($buildText, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
if (-not $codeMatch.Success) { throw 'versionCode not found in app/build.gradle.kts' }
$versionCode = [int]$codeMatch.Groups[1].Value

if ([string]::IsNullOrWhiteSpace($Tag)) { $Tag = "v$versionName" }

# The APK is named <app>-<version>.apk by app/build.gradle.kts. The search is by prefix
# so that the version is still written down in exactly one place; a directory holding two
# versions is reported rather than guessed at.
$apks = @(Get-ChildItem -LiteralPath $ApkDir -Filter 'FN-tvphoto-*.apk' -File -ErrorAction SilentlyContinue)
if ($apks.Count -eq 0) { throw "no release APK in $ApkDir - run assembleRelease first" }
$apk = $apks | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apks.Count -gt 1) {
  Write-Host ("release-manifest: {0} APKs match; taking the newest, {1}" -f $apks.Count, $apk.Name)
}

# The APK's own name has to carry the version the manifest claims, or the download the
# app is about to offer would 404 on a file that was never uploaded.
$expectedName = "FN-tvphoto-$versionName.apk"
if ($apk.Name -ne $expectedName) {
  Write-Warning "release-manifest: newest APK is $($apk.Name), but version $versionName expects $expectedName"
}

$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
$url = "$Site/$Repo/releases/download/$Tag/$($apk.Name)"

$manifest = [ordered]@{
  versionName = $versionName
  versionCode = $versionCode
  apk         = $apk.Name
  apkUrl      = $url
  sha256      = $hash
  sizeBytes   = $apk.Length
}

$json = $manifest | ConvertTo-Json -Depth 3
$outPath = if ([string]::IsNullOrWhiteSpace($OutFile)) { Join-Path $ApkDir 'version.json' } else { $OutFile }
# No BOM: the app parses this straight from the response body, and a leading byte order
# mark is not JSON.
[System.IO.File]::WriteAllText($outPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "release-manifest: $versionName ($versionCode) for $Site/$Repo"
Write-Host "  apk     $($apk.Name)  $([math]::Round($apk.Length / 1MB, 1)) MiB"
Write-Host "  url     $url"
Write-Host "  sha256  $hash"
Write-Host "  wrote   $outPath"
Write-Host "  attach it to release $Tag on that site as version.json, or commit it to the repository"
