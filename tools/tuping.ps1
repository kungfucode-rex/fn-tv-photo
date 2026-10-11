<#
  Builds, packages and deploys the 图评 (tuping) server.

  The service runs on a NAS that has no Go toolchain, so the binary is always
  cross-built here and copied there — exactly the shape docs/tuping-design.md
  §11.5 describes. Nothing is installed system-wide: the Go toolchain lives in
  $ToolchainRoot next to the Android one and is deleted by removing a directory.

  Examples (works in Windows PowerShell 5.1 and PowerShell 7):
    powershell -File tools/tuping.ps1 -Tasks build
    powershell -File tools/tuping.ps1 -Tasks build,dist      # both architectures
    powershell -File tools/tuping.ps1 -Tasks test
    powershell -File tools/tuping.ps1 -Tasks run -BaseUrl http://192.168.31.51:8080
    powershell -File tools/tuping.ps1 -Tasks run -AdminToken my-own-password
    powershell -File tools/tuping.ps1 -Tasks deploy -NasHost 192.168.31.14

  `pwsh` does not exist on a machine that only ships Windows PowerShell, so the examples
  use `powershell`; both work. Every Go command is run from server/, where the module is.

  This file is deliberately pure ASCII. Windows PowerShell decodes a .ps1 as ANSI
  unless it carries a UTF-8 BOM, so a Chinese character in a literal here would
  arrive mangled — the same lesson tools/publish-apk.ps1 records.
#>
[CmdletBinding()]
param(
  # Space-separated list: build, test, vet, dist, run, deploy, clean
  [string[]]$Tasks = @('build'),

  # Where the self-contained Go toolchain lives.
  [string]$ToolchainRoot = 'F:\go-toolchain',

  # Where the module cache lives. Inside the toolchain root so deleting one
  # directory cleans up everything this ever downloaded.
  [string]$ModuleCache,

  # Output directory for cross-built binaries.
  [string]$Dist = 'dist',

  [string]$Version = '0.1.0',

  # Listening address for -Tasks run.
  [string]$Addr = '0.0.0.0:8080',

  # Admin password for -Tasks run. Empty mints one and prints it, which is the useful
  # default: with no TUPING_ADMIN_TOKEN at all, the H5 page hides its 管理员登录 link
  # and /admin answers 404 (docs/tuping-design.md §17.15), and a dev run that silently
  # has no admin is exactly how "the feature is missing" gets reported.
  [string]$AdminToken = '',

  # Base URL written into QR codes. Must be an address a PHONE can reach; this is
  # the one setting whose mistake shows up as "I scanned it and nothing happened".
  [string]$BaseUrl = 'http://127.0.0.1:8080',

  # Deploy target.
  [string]$NasHost = '',
  [string]$NasPath = '/vol1/docker/tuping',
  [string]$NasUser = 'root'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ServerRoot = Join-Path $RepoRoot 'server'
$DistPath = if ([System.IO.Path]::IsPathRooted($Dist)) { $Dist } else { Join-Path $RepoRoot $Dist }
if (-not $ModuleCache) { $ModuleCache = Join-Path $ToolchainRoot 'modcache' }

function Write-Step([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok([string]$m) { Write-Host "  [ok] $m" -ForegroundColor Green }
function Write-Info([string]$m) { Write-Host "  ...  $m" -ForegroundColor Gray }
function Write-Warn([string]$m) { Write-Host "  [!]  $m" -ForegroundColor Yellow }

# PowerShell 5.1 promotes a native tool's stderr to an ErrorRecord, which aborts
# the run while $ErrorActionPreference is 'Stop'. Go writes progress to stderr
# legitimately (module downloads), so native calls run under 'Continue'.
function Invoke-Native([scriptblock]$Body) {
  $old = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Body 2>&1 | ForEach-Object { "$_" } }
  finally { $ErrorActionPreference = $old }
}

# ---------------------------------------------------------------- toolchain

$GoExe = Join-Path $ToolchainRoot 'go\bin\go.exe'
if (-not (Test-Path $GoExe)) {
  # Fall back to whatever is on PATH, so a machine that already has Go works.
  $onPath = (Get-Command go -ErrorAction SilentlyContinue).Source
  if ($onPath) {
    $GoExe = $onPath
    Write-Warn "using the Go on PATH ($GoExe) instead of $ToolchainRoot"
  } else {
    throw "no Go toolchain. Run tools/setup-go.ps1 first, or install Go and put it on PATH."
  }
}

function Set-GoEnv {
  $env:GOTOOLCHAIN = 'local'
  $env:GOMODCACHE = $ModuleCache
  $env:GOPROXY = 'https://goproxy.cn,direct'
  $env:GOSUMDB = 'sum.golang.google.cn'
  $env:GOWORK = 'off'
  # Pure Go, no cgo: this is what makes the cross-build below work at all.
  $env:CGO_ENABLED = '0'
}

function Invoke-Go([string[]]$GoArgs) {
  Set-GoEnv
  # Every Go command runs from server/. The module lives there and not at the repository
  # root, so a `go build ./...` issued from the root fails with "cannot find main module"
  # -- which is exactly what the first version of this script did, because it was only
  # ever run from inside server/ by hand.
  Push-Location $ServerRoot
  try {
    Invoke-Native { & $GoExe @GoArgs }
  } finally {
    Pop-Location
  }
}

# ---------------------------------------------------------------- tasks

function Invoke-Build {
  Write-Step 'build (host)'
  $out = Invoke-Go @('build', '-trimpath', '-o', (Join-Path $DistPath 'tupingd.exe'), './cmd/tupingd')
  $out | ForEach-Object { Write-Info $_ }
  if ($LASTEXITCODE -ne 0) { throw "go build failed ($LASTEXITCODE)" }
  Write-Ok "built $(Join-Path $DistPath 'tupingd.exe')"
}

function Invoke-Vet {
  Write-Step 'vet'
  $out = Invoke-Go @('vet', './...')
  $out | ForEach-Object { Write-Info $_ }
  if ($LASTEXITCODE -ne 0) { throw "go vet failed ($LASTEXITCODE)" }
  Write-Ok 'vet clean'
}

function Invoke-Test {
  Write-Step 'test'
  $out = Invoke-Go @('test', './...', '-count=1')
  $out | ForEach-Object { Write-Info $_ }
  if ($LASTEXITCODE -ne 0) { throw "go test failed ($LASTEXITCODE)" }
  Write-Ok 'tests passed'
}

function Invoke-Dist {
  Write-Step "dist (linux/amd64 + linux/arm64, version $Version)"
  New-Item -ItemType Directory -Force -Path $DistPath | Out-Null

  # The NAS determines which of these is used; the deployment README's first step
  # is `uname -m`, because that is the whole decision.
  foreach ($arch in @('amd64', 'arm64')) {
    Set-GoEnv
    $env:GOOS = 'linux'
    $env:GOARCH = $arch
    $out = Join-Path $DistPath "tupingd-linux-$arch"
    $ldflags = "-s -w -X main.version=$Version"
    $log = Invoke-Go @('build', '-trimpath', '-ldflags', $ldflags, '-o', $out, './cmd/tupingd')
    $log | ForEach-Object { Write-Info $_ }
    if ($LASTEXITCODE -ne 0) { throw "go build ($arch) failed ($LASTEXITCODE)" }
    $size = [math]::Round((Get-Item $out).Length / 1MB, 2)
    Write-Ok "tupingd-linux-$arch  $size MiB"
  }

  # The files §11.5 says a deployer gets, next to the binaries so a release
  # attachment is just this directory. README.md is copied rather than written
  # here, so an edit to server/README.md reaches deployers by rebuilding dist --
  # which is why dist/README.md is never edited directly.
  #
  # docker-compose.yml builds from this directory; docker-compose.image.yml pulls
  # the published image and is the one a deployer should normally use (§17.22).
  Copy-Item (Join-Path $ServerRoot 'Dockerfile') $DistPath -Force
  Copy-Item (Join-Path $ServerRoot 'docker-compose.yml') $DistPath -Force
  Copy-Item (Join-Path $ServerRoot 'docker-compose.image.yml') $DistPath -Force
  Copy-Item (Join-Path $ServerRoot 'README.md') $DistPath -Force
  Copy-Item (Join-Path $ServerRoot '.dockerignore') $DistPath -Force
  Write-Ok "Dockerfile, both compose files, README.md and .dockerignore copied to $DistPath"
}

function Invoke-Run {
  Write-Step "run (http://$Addr)"
  if ($BaseUrl -match 'localhost|127\.0\.0\.1|0\.0\.0\.0') {
    # ${BaseUrl} rather than $BaseUrl: Windows PowerShell 5.1 parses `$BaseUrl:` as a
    # drive-qualified variable reference and refuses to load the file at all. This is the
    # same class of trap the note at the top of this file records, one level down: it is
    # not about encoding but about a colon immediately after a variable name.
    Write-Warn "BaseUrl is ${BaseUrl}: a phone will not be able to open that QR code."
    Write-Warn "Pass -BaseUrl http://<this machine's LAN IP>:8080 to test the real path."
  }
  $env:TUPING_BASE_URL = $BaseUrl
  if (-not $env:TUPING_SECRET) { $env:TUPING_SECRET = 'dev-secret-change-me' }
  if (-not $env:TUPING_DB) { $env:TUPING_DB = Join-Path $RepoRoot 'artifacts\tmp\tuping-dev.db' }
  if (-not $env:TUPING_THUMBS) { $env:TUPING_THUMBS = Join-Path $RepoRoot 'artifacts\tmp\tuping-dev-thumbs' }

  # The admin door exists only while TUPING_ADMIN_TOKEN is set (see the parameter above).
  # Minting one and printing it means the command this file documents produces a server
  # whose 管理员登录 link actually works. A password read off a console is not a secret
  # worth keeping -- pass -AdminToken, or set the variable before starting, to use yours.
  if (-not $env:TUPING_ADMIN_TOKEN) {
    if ($AdminToken) { $env:TUPING_ADMIN_TOKEN = $AdminToken }
    else { $env:TUPING_ADMIN_TOKEN = ([guid]::NewGuid().ToString('N')).Substring(0, 16) }
  }
  New-Item -ItemType Directory -Force -Path (Split-Path $env:TUPING_DB) | Out-Null

  Write-Info "db      = $env:TUPING_DB"
  Write-Info "thumbs  = $env:TUPING_THUMBS"
  Write-Info "admin   = $env:TUPING_ADMIN_TOKEN"
  Write-Info "login   = $($BaseUrl.TrimEnd('/'))/admin"
  Write-Info 'press Ctrl+C to stop'
  Invoke-Go @('run', './cmd/tupingd', '-addr', $Addr)
}

function Invoke-Deploy {
  if (-not $NasHost) { throw '-Tasks deploy needs -NasHost <nas address>' }
  Write-Step "deploy to ${NasUser}@${NasHost}:$NasPath"

  $binary = Join-Path $DistPath 'tupingd-linux-amd64'
  if (-not (Test-Path $binary)) { throw "$binary is missing; run -Tasks dist first" }

  # scp only: the NAS has no Go toolchain, and ssh is the only thing it needs to
  # have. -O forces the classic protocol, which older NAS sshd implementations
  # require and which newer scp clients default away from.
  #
  # The upload lands under a staging name and is moved into place on the NAS. The
  # order matters, because the Dockerfile COPYs `tupingd-linux-amd64` by name: that
  # name has to hold a complete binary before the build reads the context. The
  # first version of this task renamed the freshly uploaded file to tupingd.new
  # and then built, so the COPY could only fail with "not found" -- the deploy path
  # was never exercised, and this is the bug that hid in it.
  $target = "${NasUser}@${NasHost}:$NasPath/tupingd.new"
  $out = Invoke-Native { & scp -O $binary $target }
  $out | ForEach-Object { Write-Info $_ }
  if ($LASTEXITCODE -ne 0) { throw "scp failed ($LASTEXITCODE)" }

  # This replaces the binary in an existing compose project and rebuilds it; it does
  # not create the project. The first deployment is a panel job (server/README.md
  # steps 3-5), because that is where the Dockerfile, the compose file and the data
  # directory get their ownership settled.
  $remote = @"
set -e
mv -f $NasPath/tupingd.new $NasPath/tupingd-linux-amd64
chmod +x $NasPath/tupingd-linux-amd64
cd $NasPath
docker compose up -d --build
"@
  $out = Invoke-Native { & ssh "${NasUser}@${NasHost}" $remote }
  $out | ForEach-Object { Write-Info $_ }
  if ($LASTEXITCODE -ne 0) { throw "ssh failed ($LASTEXITCODE)" }
  Write-Ok 'deployed; check the container log for the listening line'
}

function Invoke-Clean {
  Write-Step 'clean'
  Remove-Item -Recurse -Force $DistPath -ErrorAction SilentlyContinue
  Write-Ok 'dist removed (the toolchain and module cache were left alone)'
}

New-Item -ItemType Directory -Force -Path $DistPath | Out-Null

# `-Tasks build,dist` is not an array when the script is launched with `powershell -File`:
# PowerShell passes every argument after -File as a literal string, so the comma survives
# into the value and `switch` matches nothing. Splitting it here means both spellings a
# person is likely to type work -- `-Tasks build,dist` and `-Tasks build -Tasks dist`.
$taskList = @()
foreach ($entry in $Tasks) {
  $taskList += ($entry -split '[,;\s]+' | Where-Object { $_ -ne '' })
}

foreach ($task in $taskList) {
  switch ($task.ToLowerInvariant()) {
    'build' { Invoke-Build }
    'vet' { Invoke-Vet }
    'test' { Invoke-Test }
    'dist' { Invoke-Dist }
    'run' { Invoke-Run }
    'deploy' { Invoke-Deploy }
    'clean' { Invoke-Clean }
    default { throw "unknown task '$task' (build, vet, test, dist, run, deploy, clean)" }
  }
}

Write-Host "`nTUPING_OK" -ForegroundColor Green
