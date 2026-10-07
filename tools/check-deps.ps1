<#
  Reads `minCompileSdk` / `minAndroidGradlePluginVersion` out of candidate AARs so
  dependency versions can be pinned to what this SDK (platform 36, AGP 8.13.2)
  can actually build. Newest-first: the first version that fits wins.
#>
[CmdletBinding()]
param(
  [int]$MaxCompileSdk = 36,
  [string]$MinAgp = '8.13.2'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Google = 'https://dl.google.com/dl/android/maven2'
$Central = 'https://repo1.maven.org/maven2'

$candidates = @(
  @{ g = 'androidx.core'; a = 'core-ktx'; v = @('1.19.1', '1.18.0', '1.17.0', '1.16.0') },
  @{ g = 'androidx.activity'; a = 'activity-compose'; v = @('1.13.0', '1.12.0', '1.11.0', '1.10.1') },
  @{ g = 'androidx.lifecycle'; a = 'lifecycle-runtime-compose'; v = @('2.11.0', '2.10.0', '2.9.4') },
  @{ g = 'androidx.lifecycle'; a = 'lifecycle-viewmodel-compose'; v = @('2.11.0', '2.10.0', '2.9.4') },
  @{ g = 'androidx.compose.ui'; a = 'ui'; v = @('1.12.1', '1.11.0', '1.10.0', '1.9.0') },
  @{ g = 'androidx.compose.material3'; a = 'material3'; v = @('1.4.0', '1.3.2', '1.3.0') },
  @{ g = 'androidx.tv'; a = 'tv-material'; v = @('1.1.0', '1.0.1', '1.0.0') },
  @{ g = 'androidx.tv'; a = 'tv-foundation'; v = @('1.0.0') },
  @{ g = 'androidx.media3'; a = 'media3-exoplayer'; v = @('1.11.1', '1.10.1', '1.9.0', '1.8.0') },
  @{ g = 'androidx.media3'; a = 'media3-ui'; v = @('1.11.1', '1.10.1', '1.9.0', '1.8.0') },
  @{ g = 'io.coil-kt.coil3'; a = 'coil-compose'; v = @('3.6.3', '3.5.0', '3.4.0', '3.3.0') },
  @{ g = 'io.coil-kt.coil3'; a = 'coil-network-okhttp'; v = @('3.6.3', '3.5.0', '3.4.0', '3.3.0') },
  @{ g = 'com.squareup.okhttp3'; a = 'okhttp'; v = @('5.5.0', '5.2.3', '5.0.0', '4.12.0') }
)

function Get-Metadata([string]$group, [string]$artifact, [string]$version) {
  $gp = $group -replace '\.', '/'
  # The `-android` variant is the real artifact for KMP libraries; the bare name is
  # a stub AAR with no metadata, and reading that first produced false passes.
  foreach ($base in @($Google, $Central)) {
    foreach ($name in @("$artifact-android", $artifact)) {
      $url = "$base/$gp/$name/$version/$name-$version.aar"
      $tmp = Join-Path $env:TEMP "depcheck-$(New-Guid).aar"
      try {
        & curl.exe -L --fail --silent --show-error -o $tmp $url 2>$null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp)) { continue }
        $zip = [System.IO.Compression.ZipFile]::OpenRead($tmp)
        try {
          $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/com/android/build/gradle/aar-metadata.properties' }
          if (-not $entry) {
            # A stub with no constraint metadata tells us nothing; keep looking.
            continue
          }
          $reader = New-Object System.IO.StreamReader($entry.Open())
          $text = $reader.ReadToEnd()
          $reader.Close()
          $minSdk = 1
          $minAgp = 'n/a'
          foreach ($line in $text -split "`n") {
            if ($line -match '^\s*minCompileSdk\s*=\s*(\d+)') { $minSdk = [int]$Matches[1] }
            if ($line -match '^\s*minAndroidGradlePluginVersion\s*=\s*(\S+)') { $minAgp = $Matches[1] }
          }
          return @{ found = $true; minSdk = $minSdk; minAgp = $minAgp; src = $url }
        } finally { $zip.Dispose() }
      } catch {
        # not an AAR (plain jar) or not published at this coordinate
      } finally {
        if (Test-Path $tmp) { Remove-Item -Force $tmp -ErrorAction SilentlyContinue }
      }
    }
  }
  return @{ found = $false }
}

function Compare-Ver([string]$a, [string]$b) {
  if ($a -eq 'n/a') { return $false }
  $pa = ($a -split '[.\-+]') | ForEach-Object { if ($_ -match '^\d+$') { [int]$_ } else { 0 } }
  $pb = ($b -split '[.\-+]') | ForEach-Object { if ($_ -match '^\d+$') { [int]$_ } else { 0 } }
  for ($i = 0; $i -lt [Math]::Max($pa.Count, $pb.Count); $i++) {
    $x = if ($i -lt $pa.Count) { $pa[$i] } else { 0 }
    $y = if ($i -lt $pb.Count) { $pb[$i] } else { 0 }
    if ($x -ne $y) { return $x -gt $y }
  }
  return $false
}

Write-Host "Target: compileSdk <= $MaxCompileSdk, AGP <= $MinAgp`n"

$picks = @()
foreach ($c in $candidates) {
  $chosen = $null
  $report = @()
  foreach ($v in $c.v) {
    $m = Get-Metadata $c.g $c.a $v
    if (-not $m.found) { $report += "$v=not-an-aar"; continue }
    $tooNewAgp = Compare-Ver $m.minAgp $MinAgp
    $ok = ($m.minSdk -le $MaxCompileSdk) -and (-not $tooNewAgp)
    $report += "$v(sdk$($m.minSdk),agp$($m.minAgp))$(if($ok){'OK'}else{'X'})"
    if ($ok -and -not $chosen) { $chosen = $v; break }
  }
  $label = "$($c.g):$($c.a)"
  if ($chosen) {
    Write-Host ("{0,-46} => {1}" -f $label, $chosen) -ForegroundColor Green
    $picks += "$($c.g):$($c.a):$chosen"
  } else {
    Write-Host ("{0,-46} => NONE FITS   [{1}]" -f $label, ($report -join ', ')) -ForegroundColor Red
  }
}

Write-Host "`n--- pins ---"
$picks | ForEach-Object { Write-Host "  $_" }
