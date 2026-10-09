# =============================================================================
#  build-full-package.ps1 -- the "zero-install" submission zip
#
#  Produces  <DistRoot>\<rootName>-(double-click-run + source + docs)-<yyyyMMdd>.zip
#  with this layout (names are built from code points further down, so this
#  script itself stays pure ASCII -- Windows PowerShell 5.1 would otherwise read
#  the Chinese literals as ANSI and mangle every path):
#      <rootName>/
#        0-READ-ME-FIRST.txt             (entry note, UTF-8 WITH BOM so Notepad is happy)
#        1-run-zero-install/            (bundled runtime + portable MySQL + Redis + jar
#                                        + start cmd / stop cmd / usage txt)
#        2-source/                      (backend/ + frontend/ + README.md)
#        3-docs/                        (docs/)
#        4-screenshots/                 (shots/ + gallery html)
#
#  Why this exists: the light package needs a grader to install JDK + MySQL +
#  Redis + Node and run SQL. This one needs NOTHING but an unzip -- MySQL data,
#  the Java runtime and Redis all travel inside, the jar serves the built SPA, so
#  a single double-click opens http://localhost:10087.
#
#  Sources it assembles from:
#    - docs/05-submission/README-FIRST.txt            (entry note)
#    - docs/05-submission/run-version/*               (start/stop/usage)
#    - D:\DevEnv\dist\portable\{mysql,redis,runtime}  (portable stack, built separately)
#    - backend/target/campusswap-backend-1.0.0.jar    (fat jar WITH the SPA inside)
#    - the light staging tree built by build-submission.ps1 (source/docs/screenshots)
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs/05-submission/build-full-package.ps1
#    ... -NoZip -NoDesktopCopy -PortableRoot D:\DevEnv\dist\portable
#  Exit code: 0 = ok, 1 = verification failed
#  Pure ASCII: every Chinese name lives in package-manifest.json / the .txt files
#  / the file names produced from the manifest, never in this script's syntax.
# =============================================================================
param(
    [string]$DistRoot = 'D:\DevEnv\dist',
    [string]$PortableRoot = 'D:\DevEnv\dist\portable',
    [string]$DesktopDir = 'D:\Six Gods\Desktop',
    [switch]$NoZip,
    [switch]$NoDesktopCopy
)

$ErrorActionPreference = 'Stop'
$Utf8Bom = New-Object System.Text.UTF8Encoding($true)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$MaxBytes = 2GB
$problems = @()
function Add-Problem([string]$t) { $script:problems += $t; Write-Host ('  !! ' + $t) }
function Format-Size { param([long]$b)
    if ($b -ge 1GB) { return ('{0:N2} GB' -f ($b / 1GB)) }
    if ($b -ge 1MB) { return ('{0:N1} MB' -f ($b / 1MB)) }
    return ('{0:N0} KB' -f ($b / 1KB)) }

$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..')).Path
$manifest = [System.IO.File]::ReadAllText((Join-Path $here 'package-manifest.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$rootName = $manifest.rootName

# Section / file names, built from code points so this file stays ASCII-only.
# (decoded: 1-run-zero-install / 2-source / 3-docs / 4-screenshots /
#           0-READ-ME-FIRST.txt / start cmd / stop cmd /
#           usage txt / gallery html)  -- see package-manifest.json for the real names
$secRun = -join ([char]0x4E00, [char]0x002D, [char]0x53CC, [char]0x51FB, [char]0x8FD0, [char]0x884C, [char]0xFF08, [char]0x514D, [char]0x5B89, [char]0x88C5, [char]0xFF09)
$secSrc = -join ([char]0x4E8C, [char]0x002D, [char]0x6E90, [char]0x7801)
$secDoc = -join ([char]0x4E09, [char]0x002D, [char]0x6587, [char]0x6863)
$secShot = -join ([char]0x56DB, [char]0x002D, [char]0x6548, [char]0x679C, [char]0x622A, [char]0x56FE)
$entryName = -join ([char]0x96F6, [char]0x002D, [char]0x5148, [char]0x770B, [char]0x8FD9, [char]0x91CC, [char]0x002E, [char]0x0074, [char]0x0078, [char]0x0074)
$startName = -join ([char]0x0031, [char]0x002D, [char]0x542F, [char]0x52A8, [char]0x7F51, [char]0x7AD9, [char]0xFF08, [char]0x53CC, [char]0x51FB, [char]0x6211, [char]0xFF09, [char]0x002E, [char]0x0063, [char]0x006D, [char]0x0064)
$stopName = -join ([char]0x0032, [char]0x002D, [char]0x505C, [char]0x6B62, [char]0x7F51, [char]0x7AD9, [char]0xFF08, [char]0x53CC, [char]0x51FB, [char]0x6211, [char]0xFF09, [char]0x002E, [char]0x0063, [char]0x006D, [char]0x0064)
$usageName = -join ([char]0x4F7F, [char]0x7528, [char]0x8BF4, [char]0x660E, [char]0x002E, [char]0x0074, [char]0x0078, [char]0x0074)
$galleryName = -join ([char]0x6253, [char]0x5F00, [char]0x6211, [char]0x002D, [char]0x770B, [char]0x754C, [char]0x9762, [char]0x002E, [char]0x0068, [char]0x0074, [char]0x006D, [char]0x006C)
$zipSuffix = -join ([char]0x53CC, [char]0x51FB, [char]0x8FD0, [char]0x884C, [char]0x002B, [char]0x6E90, [char]0x7801, [char]0x002B, [char]0x6587, [char]0x6863)

$stageRoot = Join-Path $DistRoot 'campusswap-full'
$stage = Join-Path $stageRoot $rootName
$lightStage = Join-Path (Join-Path $DistRoot 'campusswap-submission') $rootName

Write-Host '============================================================='
Write-Host ' CampusSwap FULL (zero-install) package builder'
Write-Host (' repo      : ' + $repo)
Write-Host (' staging   : ' + $stage)
Write-Host (' portable  : ' + $PortableRoot)
Write-Host '============================================================='

# --- 1. sources: refresh the light staging tree (source/docs/screenshots) ------
Write-Host ''
Write-Host '[1/6] refreshing source + docs + screenshots via build-submission.ps1 ...'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'build-submission.ps1') -DistRoot $DistRoot -NoZip -NoDesktopCopy | Out-Host
if (-not (Test-Path -LiteralPath $lightStage)) { Add-Problem ('light staging missing after build: ' + $lightStage) }

# --- 2. fresh staging ---------------------------------------------------------
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null

# --- 3. entry note ------------------------------------------------------------
Write-Host ''
Write-Host '[2/6] entry note ...'
$firstSrc = Join-Path $here 'README-FIRST.txt'
if (Test-Path -LiteralPath $firstSrc) {
    $text = [System.IO.File]::ReadAllText($firstSrc, [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText((Join-Path $stage $entryName), $text, $Utf8Bom)
    Write-Host ('    ' + $entryName)
} else { Add-Problem 'README-FIRST.txt missing' }

# --- 4. run version ----------------------------------------------------------
Write-Host ''
Write-Host '[3/6] zero-install run version ...'
$runDir = Join-Path $stage $secRun
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

$jarSrc = Join-Path $repo 'backend\target\campusswap-backend-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarSrc)) { Add-Problem ('fat jar missing (run: cd frontend; pnpm run build; cd ..\backend; mvnw clean package -DskipTests): ' + $jarSrc) }
else {
    New-Item -ItemType Directory -Path (Join-Path $runDir 'app') -Force | Out-Null
    Copy-Item -LiteralPath $jarSrc -Destination (Join-Path $runDir 'app\campusswap-backend-1.0.0.jar') -Force
    Write-Host ('    app\campusswap-backend-1.0.0.jar  ' + (Format-Size (Get-Item $jarSrc).Length))
}
foreach ($part in @('mysql', 'redis', 'runtime')) {
    $src = Join-Path $PortableRoot $part
    if (-not (Test-Path -LiteralPath $src)) { Add-Problem ('portable part missing: ' + $src); continue }
    Write-Host ('    copying ' + $part + ' ...')
    & robocopy $src (Join-Path $runDir $part) /E /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { Add-Problem ('robocopy failed for ' + $part + ' (exit ' + $LASTEXITCODE + ')') }
    $mb = [math]::Round(((Get-ChildItem (Join-Path $runDir $part) -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host ('      ' + $part + ' = ' + $mb + ' MB')
}
$rv = Join-Path $here 'run-version'
if (Test-Path -LiteralPath $rv) {
    Copy-Item -LiteralPath (Join-Path $rv '1-start.cmd') -Destination (Join-Path $runDir $startName) -Force
    Copy-Item -LiteralPath (Join-Path $rv '2-stop.cmd') -Destination (Join-Path $runDir $stopName) -Force
    $usageText = [System.IO.File]::ReadAllText((Join-Path $rv $usageName), [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText((Join-Path $runDir $usageName), $usageText, $Utf8Bom)
    Write-Host ('    ' + $startName + ' / ' + $stopName + ' / ' + $usageName)
} else { Add-Problem 'run-version folder missing' }

# --- 5. source / docs / screenshots in numbered sections ----------------------
Write-Host ''
Write-Host '[4/6] source, docs and screenshots ...'
$srcDir = Join-Path $stage $secSrc
New-Item -ItemType Directory -Path $srcDir -Force | Out-Null
foreach ($item in @('backend', 'frontend', 'README.md')) {
    $s = Join-Path $lightStage $item
    if (-not (Test-Path -LiteralPath $s)) { Add-Problem ('light stage is missing ' + $item); continue }
    if ((Get-Item -LiteralPath $s).PSIsContainer) { & robocopy $s (Join-Path $srcDir $item) /E /NFL /NDL /NJH /NJS /NP | Out-Null }
    else { Copy-Item -LiteralPath $s -Destination (Join-Path $srcDir $item) -Force }
}
$docDir = Join-Path $stage $secDoc
& robocopy (Join-Path $lightStage 'docs') $docDir /E /NFL /NDL /NJH /NJS /NP | Out-Null
$shotDir = Join-Path $stage $secShot
New-Item -ItemType Directory -Path $shotDir -Force | Out-Null
& robocopy (Join-Path $lightStage $manifest.shotsDir) (Join-Path $shotDir $manifest.shotsDir) /E /NFL /NDL /NJH /NJS /NP | Out-Null
$gallerySrc = Join-Path $lightStage $manifest.galleryFile
if (Test-Path -LiteralPath $gallerySrc) {
    # the gallery references shots/ relatively -> it must sit next to the folder
    Copy-Item -LiteralPath $gallerySrc -Destination (Join-Path $shotDir $galleryName) -Force
    Write-Host ('    ' + $secShot + '\' + $galleryName + ' + ' + $manifest.shotsDir + '\')
} else { Add-Problem 'gallery html missing in light stage' }
# the light entry guide is superseded by the new 0-READ-ME-FIRST.txt at the root;
# keep a copy of it inside the docs section for completeness
$lightGuide = Join-Path $lightStage $manifest.entryDoc
if (Test-Path -LiteralPath $lightGuide) {
    Copy-Item -LiteralPath $lightGuide -Destination (Join-Path $docDir $manifest.entryDoc) -Force
}
foreach ($drop in @($manifest.shotsDir, $manifest.galleryFile, $manifest.entryDoc)) {
    $p = Join-Path $srcDir $drop
    if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force }
}

# --- 6. verify -----------------------------------------------------------------
Write-Host ''
Write-Host '[5/6] verifying ...'
$allFiles = @(Get-ChildItem -LiteralPath $stage -Recurse -File -Force)
$totalBytes = ($allFiles | Measure-Object Length -Sum).Sum
$excludeDirs = @($manifest.excludeDirNames | ForEach-Object { $_.ToLower() })
foreach ($d in @(Get-ChildItem -LiteralPath $stage -Recurse -Directory -Force)) {
    $name = $d.Name.ToLower()
    if ($excludeDirs -contains $name) { Add-Problem ('excluded dir leaked: ' + $d.FullName.Replace($stage, '')) }
    # the portable MySQL data dir is legitimately named "data"; anything else excluded-looking is a bug
}
$musts = @(
    ($secRun + '\app\campusswap-backend-1.0.0.jar'),
    ($secRun + '\runtime\bin\java.exe'),
    ($secRun + '\mysql\bin\mysqld.exe'),
    ($secRun + '\mysql\bin\mysqladmin.exe'),
    ($secRun + '\mysql\data\campusswap_db'),
    ($secRun + '\redis\redis-server.exe'),
    ($secRun + '\' + $startName),
    ($secRun + '\' + $stopName),
    ($secRun + '\' + $usageName),
    $entryName,
    ($secSrc + '\backend\pom.xml'),
    ($secSrc + '\frontend\package.json'),
    ($secDoc + '\MASTER-PLAN.md'),
    ($secDoc + '\03-qa-review\CODE_REVIEW.md'),
    ($secShot + '\' + $galleryName)
)
foreach ($m in $musts) { if (-not (Test-Path -LiteralPath (Join-Path $stage $m))) { Add-Problem ('required file missing: ' + $m) } }
$shots = @(Get-ChildItem -LiteralPath (Join-Path $shotDir $manifest.shotsDir) -Filter '*.png' -ErrorAction SilentlyContinue).Count
if ($shots -lt 20) { Add-Problem ('expected >= 20 screenshots, found ' + $shots) }
if ($totalBytes -gt $MaxBytes) { Add-Problem ('staging exceeds 2 GB: ' + (Format-Size $totalBytes)) }
# the .cmd files inside the package must stay pure ASCII (cmd.exe OEM parsing)
$cmdFiles = @((Join-Path $runDir $startName), (Join-Path $runDir $stopName))
foreach ($c in $cmdFiles) {
    $bytes = [System.IO.File]::ReadAllBytes($c)
    $bad = @($bytes | Where-Object { $_ -gt 127 }).Count
    if ($bad -gt 0) { Add-Problem ('non-ASCII bytes in ' + (Split-Path $c -Leaf) + ': ' + $bad) }
}
Write-Host ('    files=' + $allFiles.Count + '  size=' + (Format-Size $totalBytes) + '  screenshots=' + $shots)

# --- 7. zip -------------------------------------------------------------------
$zipPath = ''
if (-not $NoZip) {
    Write-Host ''
    Write-Host '[6/6] zipping with tar.exe ...'
    $stamp = Get-Date -Format 'yyyyMMdd'
    $zipName = $rootName + [char]0xFF08 + $zipSuffix + [char]0xFF09 + '-' + $stamp + '.zip'
    $zipPath = Join-Path $DistRoot $zipName
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    & tar.exe -a -c -f $zipPath -C $stageRoot $rootName
    if ($LASTEXITCODE -ne 0) { Add-Problem ('tar failed: ' + $LASTEXITCODE) }
    else {
        $zipBytes = (Get-Item -LiteralPath $zipPath).Length
        if ($zipBytes -gt $MaxBytes) { Add-Problem ('zip exceeds 2 GB: ' + (Format-Size $zipBytes)) }
        Write-Host ('    ' + $zipPath)
        Write-Host ('    size=' + (Format-Size $zipBytes))
        # round trip: extract and look for the pieces with the real file system
        $probe = Join-Path $env:TEMP ('cs-fullcheck-' + (Get-Date -Format 'HHmmss'))
        if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Recurse -Force }
        New-Item -ItemType Directory -Path $probe -Force | Out-Null
        & tar.exe -xf $zipPath -C $probe
        $pr = Join-Path $probe $rootName
        $missing = @()
        foreach ($rel in $musts) { if (-not (Test-Path -LiteralPath (Join-Path $pr $rel))) { $missing += $rel } }
        $probeShots = @(Get-ChildItem -LiteralPath (Join-Path $pr ($secShot + '\' + $manifest.shotsDir)) -Filter '*.png' -ErrorAction SilentlyContinue).Count
        Write-Host ('    round-trip: root=' + (Test-Path -LiteralPath $pr) + '  missing=' + $missing.Count + '  screenshots=' + $probeShots)
        if ($missing.Count -gt 0) { Add-Problem ('round trip missing: ' + ($missing -join '; ')) }
        Remove-Item -LiteralPath $probe -Recurse -Force
        if (-not $NoDesktopCopy -and (Test-Path -LiteralPath $DesktopDir)) {
            $dst = Join-Path $DesktopDir $zipName
            Copy-Item -LiteralPath $zipPath -Destination $dst -Force
            $a = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
            $b = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash
            $flag = 'MISMATCH'; if ($a -eq $b) { $flag = 'SHA256 OK' }
            Write-Host ('    desktop copy: ' + $dst + '  ' + $flag)
        }
    }
}

Write-Host ''
Write-Host '============================================================='
if ($problems.Count -eq 0) {
    Write-Host ' RESULT: OK - zero-install package built and verified'
    if ($zipPath) { Write-Host (' zip    : ' + $zipPath) }
    Write-Host (' staging: ' + $stage)
    Write-Host '============================================================='
    exit 0
}
Write-Host (' RESULT: FAILED - ' + $problems.Count + ' problem(s)')
foreach ($p in $problems) { Write-Host ('   - ' + $p) }
Write-Host '============================================================='
exit 1
