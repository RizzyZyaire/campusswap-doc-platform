# =============================================================================
#  build-full-package.ps1 -- the "zero-install" submission zip
#
#  Produces  <DistRoot>\<rootName>（双击运行+源码+文档）-<yyyyMMdd>.zip
#
#  Layout (2026-10-09 restructured after user feedback "top level too busy and
#  three overlapping read-me files"): exactly FIVE predictable top-level items --
#      <rootName>/
#        先看这里.txt                  the only entry note (UTF-8 WITH BOM)
#        backend/                      sources
#        frontend/                     sources
#        docs/                         all documentation
#        其他（运行版+截图）/
#            双击运行（免安装）/        app/ runtime/ mysql/ redis/ + start/stop + 运行说明.txt
#            效果截图/                  gallery html + shots/ (23 png)
#
#  WHY every Chinese name is built from code points below: Windows PowerShell 5.1
#  reads a BOM-less .ps1 as ANSI, so Chinese literals in this script would be
#  mangled into broken paths. Data files (the .txt notes, the manifest) are UTF-8.
#
#  Sources it assembles:
#    - docs/05-submission/README-FIRST-full.md   (entry note)
#    - docs/05-submission/run-version/*           (start/stop/run guide)
#    - D:\DevEnv\dist\portable\{mysql,redis,runtime}
#    - backend/target/campusswap-backend-1.0.0.jar  (fat jar WITH the SPA inside)
#    - the light staging tree built by build-submission.ps1 (sources/docs/screenshots)
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs/05-submission/build-full-package.ps1
#    ... -NoZip -NoDesktopCopy -PortableRoot D:\DevEnv\dist\portable -JarPath <jar>
#  Exit code: 0 = ok, 1 = verification failed
# =============================================================================
param(
    [string]$DistRoot = 'D:\DevEnv\dist',
    [string]$PortableRoot = 'D:\DevEnv\dist\portable',
    [string]$DesktopDir = 'D:\Six Gods\Desktop',
    [string]$JarPath = '',
    [switch]$NoZip,
    [switch]$NoDesktopCopy
)

$ErrorActionPreference = 'Stop'
$Utf8Bom = New-Object System.Text.UTF8Encoding($true)
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

# --- names, decoded from code points (keeps this file ASCII-only) -------------
# NOTE: never put a Chinese trailing comment on a code line here -- PS 5.1 reads
# this file as ANSI, and the mis-decoded bytes can swallow the assignment
# (measured 2026-10-10: $extraLight and $shotDirName silently became $null).
# decoded: 0=entry note "xian kan zhe li .txt", 1=extra(full), 2=extra(light),
#          3=run dir, 4=shots dir, 5=start cmd, 6=stop cmd, 7=run guide, 8=zip suffix
$entryName = -join ([char]0x5148, [char]0x770B, [char]0x8FD9, [char]0x91CC, [char]0x002E, [char]0x006D, [char]0x0064)
$extraFull = -join ([char]0x5176, [char]0x4ED6, [char]0xFF08, [char]0x8FD0, [char]0x884C, [char]0x7248, [char]0x002B, [char]0x622A, [char]0x56FE, [char]0xFF09)
$extraLight = -join ([char]0x5176, [char]0x4ED6, [char]0xFF08, [char]0x622A, [char]0x56FE, [char]0xFF09)
$runDirName = -join ([char]0x53CC, [char]0x51FB, [char]0x8FD0, [char]0x884C, [char]0xFF08, [char]0x514D, [char]0x5B89, [char]0x88C5, [char]0xFF09)
$shotDirName = -join ([char]0x6548, [char]0x679C, [char]0x622A, [char]0x56FE)
$startName = -join ([char]0x0031, [char]0x002D, [char]0x542F, [char]0x52A8, [char]0x7F51, [char]0x7AD9, [char]0xFF08, [char]0x53CC, [char]0x51FB, [char]0x6211, [char]0xFF09, [char]0x002E, [char]0x0063, [char]0x006D, [char]0x0064)
$stopName = -join ([char]0x0032, [char]0x002D, [char]0x505C, [char]0x6B62, [char]0x7F51, [char]0x7AD9, [char]0xFF08, [char]0x53CC, [char]0x51FB, [char]0x6211, [char]0xFF09, [char]0x002E, [char]0x0063, [char]0x006D, [char]0x0064)
# 8=run guide -- stays .txt on purpose: it is a plain-text note read in Notepad,
#   while the package entry note (先看这里) is .md per user request.
$runGuide = -join ([char]0x8FD0, [char]0x884C, [char]0x8BF4, [char]0x660E, [char]0x002E, [char]0x0074, [char]0x0078, [char]0x0074)
$zipSuffix = -join ([char]0x53CC, [char]0x51FB, [char]0x8FD0, [char]0x884C, [char]0x002B, [char]0x6E90, [char]0x7801, [char]0x002B, [char]0x6587, [char]0x6863)
$entryName = $entryName.Trim(); $extraFull = $extraFull.Trim(); $extraLight = $extraLight.Trim()
$runDirName = $runDirName.Trim(); $shotDirName = $shotDirName.Trim(); $runGuide = $runGuide.Trim()
foreach ($pair in @(@('entryName', $entryName), @('extraFull', $extraFull), @('extraLight', $extraLight),
                    @('runDirName', $runDirName), @('shotDirName', $shotDirName), @('runGuide', $runGuide))) {
    if ([string]::IsNullOrWhiteSpace($pair[1])) { throw ('layout constant is empty: ' + $pair[0]) }
}

$stageRoot = Join-Path $DistRoot 'campusswap-full'
$stage = Join-Path $stageRoot $rootName
$lightStage = Join-Path (Join-Path $DistRoot 'campusswap-submission') $rootName

Write-Host '============================================================='
Write-Host ' CampusSwap FULL (zero-install) package builder'
Write-Host (' repo      : ' + $repo)
Write-Host (' staging   : ' + $stage)
Write-Host (' portable  : ' + $PortableRoot)
Write-Host '============================================================='

# --- 1. refresh sources/docs/screenshots through the light builder ------------
Write-Host ''
Write-Host '[1/6] refreshing source + docs + screenshots via build-submission.ps1 ...'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $here 'build-submission.ps1') -DistRoot $DistRoot -NoZip -NoDesktopCopy | Out-Host
if (-not (Test-Path -LiteralPath $lightStage)) { Add-Problem ('light staging missing after build: ' + $lightStage) }

# --- 2. fresh staging ---------------------------------------------------------
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$extraDir = Join-Path $stage $extraFull
New-Item -ItemType Directory -Path $extraDir -Force | Out-Null

# --- 3. entry note ------------------------------------------------------------
Write-Host ''
Write-Host '[2/6] entry note ...'
$firstSrc = Join-Path $here 'README-FIRST-full.md'
if (Test-Path -LiteralPath $firstSrc) {
    $text = [System.IO.File]::ReadAllText($firstSrc, [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText((Join-Path $stage $entryName), $text, $Utf8Bom)
    Write-Host ('    ' + $entryName)
} else { Add-Problem 'README-FIRST-full.md missing' }

# --- 4. zero-install run version ---------------------------------------------
Write-Host ''
Write-Host '[3/6] zero-install run version ...'
$runDir = Join-Path $extraDir $runDirName
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

$jarSrc = $JarPath
if (-not $jarSrc) { $jarSrc = Join-Path $repo 'backend\target\campusswap-backend-1.0.0.jar' }
if (-not (Test-Path -LiteralPath $jarSrc)) { Add-Problem ('fat jar missing (run: cd frontend; pnpm build; cd ..\backend; mvnw clean package -DskipTests): ' + $jarSrc) }
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
    Copy-Item -LiteralPath (Join-Path $rv 'stop-old-instance.ps1') -Destination (Join-Path $runDir 'stop-old-instance.ps1') -Force
    $usageText = [System.IO.File]::ReadAllText((Join-Path $rv $runGuide), [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText((Join-Path $runDir $runGuide), $usageText, $Utf8Bom)
    Write-Host ('    ' + $startName + ' / ' + $stopName + ' / ' + $runGuide)
} else { Add-Problem 'run-version folder missing' }

# --- 5. sources, docs and screenshots in the new layout ----------------------
Write-Host ''
Write-Host '[4/6] source, docs and screenshots ...'
foreach ($item in @('backend', 'frontend', 'docs')) {
    $s = Join-Path $lightStage $item
    if (-not (Test-Path -LiteralPath $s)) { Add-Problem ('light stage is missing ' + $item); continue }
    & robocopy $s (Join-Path $stage $item) /E /NFL /NDL /NJH /NJS /NP | Out-Null
}
$shotDir = Join-Path $extraDir $shotDirName
New-Item -ItemType Directory -Path $shotDir -Force | Out-Null
$lightExtra = Join-Path $lightStage $extraLight
& robocopy (Join-Path $lightExtra $manifest.shotsDir) (Join-Path $shotDir $manifest.shotsDir) /E /NFL /NDL /NJH /NJS /NP | Out-Null
$gallerySrc = Join-Path $lightExtra $manifest.galleryFile
if (Test-Path -LiteralPath $gallerySrc) {
    # the gallery references shots/ relatively -> both must sit in the same folder
    Copy-Item -LiteralPath $gallerySrc -Destination (Join-Path $shotDir $manifest.galleryFile) -Force
    Write-Host ('    ' + $extraFull + '\' + $shotDirName + '\ = gallery + ' + $manifest.shotsDir + '\')
} else { Add-Problem ('gallery html missing in light staging: ' + $gallerySrc) }

# --- 6. verify ----------------------------------------------------------------
Write-Host ''
Write-Host '[5/6] verifying ...'
$allFiles = @(Get-ChildItem -LiteralPath $stage -Recurse -File -Force)
$totalBytes = ($allFiles | Measure-Object Length -Sum).Sum
$excludeDirs = @($manifest.excludeDirNames | ForEach-Object { $_.ToLower() })
$topLevel = @(Get-ChildItem -LiteralPath $stage -Force | Select-Object -ExpandProperty Name)
$expectTop = @($entryName, 'backend', 'frontend', 'docs', $extraFull)
foreach ($t in $topLevel) {
    if ($expectTop -notcontains $t) { Add-Problem ('unexpected top-level item (keep the root to 5 entries): ' + $t) }
}
foreach ($t in $expectTop) {
    if ($topLevel -notcontains $t) { Add-Problem ('expected top-level item missing: ' + $t) }
}
# junk must not leak in; "data" is legitimately part of the portable MySQL tree,
# so excluded names are only flagged outside the run version folder
$runPrefix = '\' + $extraFull + '\' + $runDirName
foreach ($d in @(Get-ChildItem -LiteralPath $stage -Recurse -Directory -Force)) {
    if ($excludeDirs -notcontains $d.Name.ToLower()) { continue }
    $rel = $d.FullName.Substring($stage.Length)
    if ($rel.StartsWith($runPrefix)) { continue }
    Add-Problem ('excluded dir leaked: ' + $rel)
}
$musts = @(
    $entryName,
    'backend\pom.xml',
    'frontend\package.json',
    'docs\MASTER-PLAN.md',
    'docs\03-qa-review\run-all-checks.ps1',
    ($extraFull + '\' + $runDirName + '\app\campusswap-backend-1.0.0.jar'),
    ($extraFull + '\' + $runDirName + '\runtime\bin\java.exe'),
    ($extraFull + '\' + $runDirName + '\mysql\bin\mysqld.exe'),
    ($extraFull + '\' + $runDirName + '\mysql\bin\mysqladmin.exe'),
    ($extraFull + '\' + $runDirName + '\mysql\data\campusswap_db'),
    ($extraFull + '\' + $runDirName + '\redis\redis-server.exe'),
    ($extraFull + '\' + $runDirName + '\' + $startName),
    ($extraFull + '\' + $runDirName + '\' + $stopName),
    ($extraFull + '\' + $runDirName + '\' + $runGuide),
    ($extraFull + '\' + $shotDirName + '\' + $manifest.galleryFile)
)
foreach ($m in $musts) { if (-not (Test-Path -LiteralPath (Join-Path $stage $m))) { Add-Problem ('required file missing: ' + $m) } }
$shots = @(Get-ChildItem -LiteralPath (Join-Path $shotDir $manifest.shotsDir) -Filter '*.png' -ErrorAction SilentlyContinue).Count
if ($shots -lt 20) { Add-Problem ('expected >= 20 screenshots, found ' + $shots) }
if ($totalBytes -gt $MaxBytes) { Add-Problem ('staging exceeds 2 GB: ' + (Format-Size $totalBytes)) }
# the .cmd files inside the package must stay pure ASCII (cmd.exe OEM parsing)
foreach ($c in @((Join-Path $runDir $startName), (Join-Path $runDir $stopName))) {
    $bytes = [System.IO.File]::ReadAllBytes($c)
    $bad = @($bytes | Where-Object { $_ -gt 127 }).Count
    if ($bad -gt 0) { Add-Problem ('non-ASCII bytes in ' + (Split-Path $c -Leaf) + ': ' + $bad) }
}
Write-Host ('    top level   : ' + ($topLevel -join ' | '))
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
        # round trip: extract and check the pieces with the real file system
        $probe = Join-Path $env:TEMP ('cs-fullcheck-' + (Get-Date -Format 'HHmmss'))
        if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Recurse -Force }
        New-Item -ItemType Directory -Path $probe -Force | Out-Null
        & tar.exe -xf $zipPath -C $probe
        $pr = Join-Path $probe $rootName
        $missing = @()
        foreach ($rel in $musts) { if (-not (Test-Path -LiteralPath (Join-Path $pr $rel))) { $missing += $rel } }
        $probeShots = @(Get-ChildItem -LiteralPath (Join-Path (Join-Path $pr $extraFull) (Join-Path $shotDirName $manifest.shotsDir)) -Filter '*.png' -ErrorAction SilentlyContinue).Count
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
