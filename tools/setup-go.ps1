<#
  Provisions the self-contained Go toolchain the 图评 server is built with.

  Everything lands under -Root (default F:\go-toolchain) so nothing is installed
  system-wide and the whole thing can be deleted in one step — the same rule the
  Android toolchain follows in tools/setup-toolchain.ps1.

  The module cache lives inside the same root, which is what makes an offline
  rebuild after the first successful one possible: nothing is fetched twice.
#>
[CmdletBinding()]
param(
  [string]$Root = 'F:\go-toolchain',
  [string]$GoVersion = '1.27.2',
  [string]$Goproxy = 'https://goproxy.cn,direct'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-Step([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok([string]$m) { Write-Host "  [ok] $m" -ForegroundColor Green }
function Write-Info([string]$m) { Write-Host "  ...  $m" -ForegroundColor Gray }

$script:Curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source

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

Write-Step "Go $GoVersion"
$goExe = Join-Path $Root 'go\bin\go.exe'
if (-not (Test-Path $goExe)) {
  $zip = Join-Path $cache "go$GoVersion.windows-amd64.zip"
  Get-File "https://go.dev/dl/go$GoVersion.windows-amd64.zip" $zip
  Write-Info 'extracting Go'
  # The archive already contains a top-level `go` directory, so it expands
  # straight into the root rather than through a temporary folder.
  Expand-Archive -LiteralPath $zip -DestinationPath $Root -Force
  Remove-Item -Force $zip
}
if (-not (Test-Path $goExe)) { throw "Go provisioning failed: $goExe missing" }

Write-Step 'environment'
$env:PATH = "$(Join-Path $Root 'go\bin');$env:PATH"
$env:GOMODCACHE = Join-Path $Root 'modcache'
$env:GOPROXY = $Goproxy
$env:GOSUMDB = 'sum.golang.google.cn'
$env:GOTOOLCHAIN = 'local'
Write-Ok ((& $goExe version) -join ' ')
Write-Ok "GOMODCACHE = $env:GOMODCACHE"
Write-Ok "GOPROXY    = $env:GOPROXY"

$envFile = Join-Path $Root 'env.ps1'
@"
`$env:PATH = "$(Join-Path $Root 'go\bin');`$env:PATH"
`$env:GOMODCACHE = '$(Join-Path $Root 'modcache')'
`$env:GOPROXY = '$Goproxy'
`$env:GOSUMDB = 'sum.golang.google.cn'
`$env:GOTOOLCHAIN = 'local'
`$env:CGO_ENABLED = '0'
"@ | Set-Content -LiteralPath $envFile -Encoding utf8
Write-Ok "env script = $envFile"

Write-Step 'module cache (first fetch)'
# Warm the cache now so the first cross-build is not a surprise full download.
$serverRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'server'
if (Test-Path (Join-Path $serverRoot 'go.mod')) {
  Push-Location $serverRoot
  try {
    & $goExe mod download all
    if ($LASTEXITCODE -ne 0) { throw "go mod download failed ($LASTEXITCODE)" }
    Write-Ok 'modules downloaded'
  } finally { Pop-Location }
} else {
  Write-Info 'server/go.mod not found; skipping the module warm-up'
}

Write-Host "`nGO_TOOLCHAIN_READY" -ForegroundColor Green
