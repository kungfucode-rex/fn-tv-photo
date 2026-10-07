<#
  Provisions a self-contained Android build toolchain for the TV Photo project.
  Everything lands under -Root (default F:\android-toolchain) so nothing is
  installed system-wide and the whole thing can be deleted in one step.
#>
[CmdletBinding()]
param(
  [string]$Root = 'F:\android-toolchain',
  [string]$JdkMajor = '17',
  [string]$CmdlineToolsBuild = '13114758',
  [string]$GradleVersion = '8.13',
  [string[]]$SdkPackages = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-Step([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok([string]$m)   { Write-Host "  [ok] $m" -ForegroundColor Green }
function Write-Info([string]$m) { Write-Host "  ...  $m" -ForegroundColor Gray }

$script:Curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source

# PowerShell 5.1 promotes native stderr to an ErrorRecord, which aborts the run
# while $ErrorActionPreference is 'Stop'. Native tools such as `java -version`
# and sdkmanager legitimately write to stderr, so run them under 'Continue'.
function Invoke-Native([scriptblock]$Body) {
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Body 2>&1 | ForEach-Object { "$_" } }
  finally { $ErrorActionPreference = $old }
}

function Get-File([string]$Url, [string]$Dest) {
  if (Test-Path $Dest) { Write-Info "cached: $(Split-Path $Dest -Leaf)"; return }
  Write-Info "downloading $(Split-Path $Dest -Leaf)"
  $tmp = "$Dest.part"
  if (Test-Path $tmp) { Remove-Item -Force $tmp }
  if ($script:Curl) {
    & $script:Curl -L --fail --silent --show-error --retry 3 --retry-delay 2 -o $tmp $Url
    if ($LASTEXITCODE -ne 0) { throw "curl failed ($LASTEXITCODE) for $Url" }
  } else {
    Invoke-WebRequest -Uri $Url -OutFile $tmp -UseBasicParsing -TimeoutSec 3600
  }
  if (-not (Test-Path $tmp) -or (Get-Item $tmp).Length -eq 0) { throw "download produced no data: $Url" }
  Move-Item -LiteralPath $tmp -Destination $Dest -Force
}

New-Item -ItemType Directory -Force -Path $Root | Out-Null
$cache = Join-Path $Root '_downloads'
New-Item -ItemType Directory -Force -Path $cache | Out-Null

# ---------------------------------------------------------------- JDK 17
Write-Step "JDK $JdkMajor"
$jdkDir = Join-Path $Root "jdk-$JdkMajor"
$javaExe = Join-Path $jdkDir 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
  $jdkZip = Join-Path $cache "jdk-$JdkMajor.zip"
  Get-File "https://api.adoptium.net/v3/binary/latest/$JdkMajor/ga/windows/x64/jdk/hotspot/normal/eclipse" $jdkZip
  Write-Info 'extracting JDK'
  if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
  $tmpX = Join-Path $Root '_jdkx'
  if (Test-Path $tmpX) { Remove-Item -Recurse -Force $tmpX }
  Expand-Archive -LiteralPath $jdkZip -DestinationPath $tmpX -Force
  $inner = Get-ChildItem $tmpX -Directory | Select-Object -First 1
  Move-Item -LiteralPath $inner.FullName -Destination $jdkDir
  Remove-Item -Recurse -Force $tmpX
  Remove-Item -Force $jdkZip
}
if (-not (Test-Path $javaExe)) { throw "JDK provisioning failed: $javaExe missing" }
$env:JAVA_HOME = $jdkDir
$env:PATH = "$jdkDir\bin;$env:PATH"
Write-Ok ((Invoke-Native { & $javaExe -version } | Select-Object -First 1))

# ------------------------------------------------- Android cmdline-tools
Write-Step 'Android cmdline-tools'
$sdkRoot = Join-Path $Root 'android-sdk'
$ctLatest = Join-Path $sdkRoot 'cmdline-tools\latest'
$sdkManager = Join-Path $ctLatest 'bin\sdkmanager.bat'
if (-not (Test-Path $sdkManager)) {
  $ctZip = Join-Path $cache "cmdline-tools-$CmdlineToolsBuild.zip"
  Get-File "https://dl.google.com/android/repository/commandlinetools-win-${CmdlineToolsBuild}_latest.zip" $ctZip
  Write-Info 'extracting cmdline-tools'
  $tmpX = Join-Path $Root '_ctx'
  if (Test-Path $tmpX) { Remove-Item -Recurse -Force $tmpX }
  Expand-Archive -LiteralPath $ctZip -DestinationPath $tmpX -Force
  New-Item -ItemType Directory -Force -Path (Split-Path $ctLatest -Parent) | Out-Null
  if (Test-Path $ctLatest) { Remove-Item -Recurse -Force $ctLatest }
  Move-Item -LiteralPath (Join-Path $tmpX 'cmdline-tools') -Destination $ctLatest
  Remove-Item -Recurse -Force $tmpX
  Remove-Item -Force $ctZip
}
if (-not (Test-Path $sdkManager)) { throw "cmdline-tools provisioning failed: $sdkManager missing" }
Write-Ok 'sdkmanager present'

# ------------------------------------------------------- Android SDK bits
Write-Step 'Android SDK packages + licences'
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
Write-Info 'accepting licences'
Invoke-Native { 1..60 | ForEach-Object { 'y' } | & $sdkManager --sdk_root=$sdkRoot --licenses } | Out-Null
Write-Info ("installing: " + ($SdkPackages -join ', '))
Invoke-Native { & $sdkManager --sdk_root=$sdkRoot @SdkPackages } | ForEach-Object { Write-Info $_ }
Write-Ok 'sdk packages installed'

# --------------------------------------------------------------- Gradle
Write-Step "Gradle $GradleVersion"
$gradleDir = Join-Path $Root "gradle-$GradleVersion"
$gradleBat = Join-Path $gradleDir 'bin\gradle.bat'
if (-not (Test-Path $gradleBat)) {
  $gZip = Join-Path $cache "gradle-$GradleVersion-bin.zip"
  Get-File "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" $gZip
  Write-Info 'extracting Gradle'
  $tmpX = Join-Path $Root '_gx'
  if (Test-Path $tmpX) { Remove-Item -Recurse -Force $tmpX }
  Expand-Archive -LiteralPath $gZip -DestinationPath $tmpX -Force
  $inner = Get-ChildItem $tmpX -Directory | Select-Object -First 1
  if (Test-Path $gradleDir) { Remove-Item -Recurse -Force $gradleDir }
  Move-Item -LiteralPath $inner.FullName -Destination $gradleDir
  Remove-Item -Recurse -Force $tmpX
  Remove-Item -Force $gZip
}
if (-not (Test-Path $gradleBat)) { throw "Gradle provisioning failed: $gradleBat missing" }
Write-Ok ((Invoke-Native { & $gradleBat --version } | Select-String 'Gradle ' | Select-Object -First 1).ToString().Trim())

# ----------------------------------------------------------------- recap
Write-Step 'RECAP'
$envFile = Join-Path $Root 'env.ps1'
@"
`$env:JAVA_HOME = '$jdkDir'
`$env:ANDROID_HOME = '$sdkRoot'
`$env:ANDROID_SDK_ROOT = '$sdkRoot'
`$env:PATH = "$jdkDir\bin;$sdkRoot\platform-tools;$gradleDir\bin;`$env:PATH"
"@ | Set-Content -LiteralPath $envFile -Encoding utf8
Write-Ok "JAVA_HOME      = $jdkDir"
Write-Ok "ANDROID_HOME   = $sdkRoot"
Write-Ok "GRADLE         = $gradleBat"
Write-Ok "env script     = $envFile"
Write-Host ''
Invoke-Native { & $sdkManager --sdk_root=$sdkRoot --list_installed } | Select-Object -Last 20
Write-Host "`nTOOLCHAIN_READY" -ForegroundColor Green
