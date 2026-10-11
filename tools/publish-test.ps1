<#
  Publishes a test build: bump the version, build the debug APK, put it on the share.

  Usage:
    pwsh -File tools/publish-test.ps1
    pwsh -File tools/publish-test.ps1 -DryRun          # say what it would do, touch nothing
    pwsh -File tools/publish-test.ps1 -GradleFile <copy> -BumpOnly   # test step 1 alone

  Three steps, and the point of this file is that none of them can be skipped:

    1. appVersionName's last number and versionCode each go up by one in
       app/build.gradle.kts;
    2. tools/build.ps1 -Tasks assembleDebug builds it;
    3. tools/publish-apk.ps1 -Variant debug copies it to the share and reads it back.

  Step 1 is not bookkeeping. A television's installer refuses to lay a build over another with
  the same versionCode, so a debug APK published without a bump is one nobody can install -
  which is exactly what happened to 1.4.7 on 2026-10-11 ("没有升级版本啊，新的装不上去"). The
  last thing this script does is read the version back out of the file on the share, because
  "published" is only worth anything if the file that landed there is the new version.

  The version journal in app/build.gradle.kts is prose and stays hand-written: this script
  prints the line to add - the number, the version, and a blank to fill in - rather than
  inventing a description for a change it knows nothing about. Add it in the same change.

  Deliberately pure ASCII: Windows PowerShell decodes a .ps1 as ANSI unless it carries a UTF-8
  BOM, so a Chinese character in a literal here would arrive mangled - the same lesson
  tools/publish-apk.ps1 records, and the reason the share name below is spelled in code points.
#>
[CmdletBinding()]
param(
  [string]$ToolchainRoot = 'F:\android-toolchain',
  # The file holding the version. Only a test of this script points it somewhere else.
  [string]$GradleFile = '',
  [switch]$BumpOnly,
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($GradleFile)) {
  $GradleFile = Join-Path $projectRoot 'app\build.gradle.kts'
}
if (-not (Test-Path -LiteralPath $GradleFile)) { throw "no such file: $GradleFile" }

# ---------------------------------------------------------------- step 1: the version

# Read and write without a byte-order mark: the file does not carry one, and adding one would
# show up as a change to every line of it.
$text = [System.IO.File]::ReadAllText($GradleFile, [System.Text.UTF8Encoding]::new($false))

$nameMatch = [regex]::Match($text, '(?m)^val appVersionName = "(\d+)\.(\d+)\.(\d+)"\s*$')
if (-not $nameMatch.Success) { throw "appVersionName not found in $GradleFile" }
$codeMatch = [regex]::Match($text, '(?m)^(\s*versionCode = )(\d+)\s*$')
if (-not $codeMatch.Success) { throw "versionCode not found in $GradleFile" }

$major = [int]$nameMatch.Groups[1].Value
$minor = [int]$nameMatch.Groups[2].Value
$patch = [int]$nameMatch.Groups[3].Value
$newName = "$major.$minor.$($patch + 1)"
$oldCode = [int]$codeMatch.Groups[2].Value
$newCode = $oldCode + 1

Write-Host "bump: $($nameMatch.Groups[0].Value.Trim()) -> $newName   versionCode $oldCode -> $newCode"

$bumped = $text
$bumped = $bumped.Remove($nameMatch.Index, $nameMatch.Length).Insert(
  $nameMatch.Index, "val appVersionName = `"$newName`"")
$codeMatch = [regex]::Match($bumped, '(?m)^(\s*versionCode = )(\d+)\s*$')
$bumped = $bumped.Remove($codeMatch.Index, $codeMatch.Length).Insert(
  $codeMatch.Index, "$($codeMatch.Groups[1].Value)$newCode")

if ($DryRun) {
  Write-Host 'dry run: nothing written, nothing built, nothing published'
  Write-Host ''
  Write-Host 'the journal line to add above versionCode, in the same change:'
  Write-Host "        // $newCode is $newName, which ..."
  exit 0
}

# A copy is checked before the real file is touched, so a regex that stopped matching cannot
# leave a half-edited gradle file behind.
$sanity = [regex]::Match($bumped, '(?m)^val appVersionName = "(\d+)\.(\d+)\.(\d+)"\s*$')
$sanityCode = [regex]::Match($bumped, '(?m)^(\s*versionCode = )(\d+)\s*$')
if (-not $sanity.Success -or $sanity.Groups[0].Value -ne "val appVersionName = `"$newName`"" -or
    [int]$sanityCode.Groups[2].Value -ne $newCode) {
  throw 'the bump did not come out as expected; nothing was written'
}

[System.IO.File]::WriteAllText($GradleFile, $bumped, [System.Text.UTF8Encoding]::new($false))
Write-Host "wrote: $GradleFile"

if ($BumpOnly) {
  Write-Host 'bump only: no build, no publish'
  exit 0
}

# ---------------------------------------------------------------- steps 2 and 3

& (Join-Path $PSScriptRoot 'build.ps1') -Tasks assembleDebug -PublishVariants debug
if ($LASTEXITCODE -ne 0) { throw "build or publish failed (exit $LASTEXITCODE)" }

# ---------------------------------------------------------------- the read-back

# The share is \\fnos-ms01\Temp\ followed by the two Chinese characters for "software"
# (U+8F6F, U+4EF6), spelled in code points for the reason in this file's header.
$share = '\\fnos-ms01\Temp\' + [char]0x8F6F + [char]0x4EF6

$aapt2 = Get-ChildItem (Join-Path $ToolchainRoot 'android-sdk\build-tools') -Directory `
    -ErrorAction SilentlyContinue |
  Sort-Object { [int]($_.Name -replace '\..*$', '') } -Descending |
  ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } |
  Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $aapt2) { throw 'aapt2 not found under build-tools; cannot read the version back' }

$published = Get-ChildItem -LiteralPath $share -Filter 'app-debug-*.apk' -File |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $published) { throw "nothing matching app-debug-*.apk on $share" }

$badging = (& $aapt2 dump badging $published.FullName 2>&1 | Out-String)
$code = [regex]::Match($badging, "versionCode='(\d+)'").Groups[1].Value
$name = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value

if ($code -ne "$newCode" -or $name -ne $newName) {
  throw "the file on the share is $name ($code), not $newName ($newCode)"
}

Write-Host ''
Write-Host "published and verified: $($published.Name)  versionName=$name versionCode=$code"
Write-Host "  $($published.FullName)"
Write-Host ''
Write-Host "the journal line to add above versionCode, in the same change:"
Write-Host "        // $newCode is $newName, which ..."
