# =============================================================================
#  build-submission.ps1 -- build the Xuexitong (learning platform) submission zip
#
#  What it produces
#    1. a staging tree  <DistRoot>\campusswap-submission\<rootName>\
#         <rootName>/<entryDoc>        = the guide           (name comes from the manifest)
#         <rootName>/<galleryFile>     = generated gallery, relative <img> into shotsDir
#         <rootName>/<shotsDir>/*.png  = curated real screenshots
#         <rootName>/README.md, docs/, backend/, frontend/   (sources + docs only)
#    2. a zip  <DistRoot>\<rootName>-<yyyyMMdd>.zip   (built with tar.exe -> UTF-8 names)
#    3. a copy of that zip on the Desktop (optional, -NoDesktopCopy to skip)
#
#  Why a script: the submission must contain ONLY what lets a teacher read the
#  code and see/run the product (no node_modules / target / dist / .git / .idea /
#  logs / uploaded files). Rebuilding it by hand invites shipping 100+ MB of junk.
#  The 2 GB learning-platform limit is asserted before (staging) and after (zip).
#
#  All Chinese names live in package-manifest.json / the guide / the gallery
#  template (UTF-8 files) so that THIS script stays pure ASCII -- Windows
#  PowerShell 5.1 reads BOM-less .ps1 as ANSI and CJK would break the parser.
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs/05-submission/build-submission.ps1
#    ... -NoZip          # only build/refresh the staging tree
#    ... -NoDesktopCopy  # do not copy the zip to the Desktop
#    ... -DistRoot D:\DevEnv\dist
#
#  Exit code: 0 = ok, 1 = verification failed (junk found / missing shots / > 2 GB)
# =============================================================================
param(
    [string]$DistRoot = 'D:\DevEnv\dist',
    [string]$DesktopDir = 'D:\Six Gods\Desktop',
    [switch]$NoZip,
    [switch]$NoDesktopCopy
)

$ErrorActionPreference = 'Stop'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$MaxBytes = 2GB

$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '..\..')).Path
$manifestPath = Join-Path $here 'package-manifest.json'
$manifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

$rootName = $manifest.rootName
$stagingParent = Join-Path $DistRoot 'campusswap-submission'
$stage = Join-Path $stagingParent $rootName
$problems = @()

function Say([string]$t) { Write-Host $t }
function Add-Problem([string]$t) { $script:problems += $t; Write-Host ('  !! ' + $t) }

function Format-Size {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return ('{0:N2} GB' -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ('{0:N1} MB' -f ($Bytes / 1MB)) }
    return ('{0:N0} KB' -f ($Bytes / 1KB))
}

# --- recursive copy with exclusions ------------------------------------------
$excludeDirs = @($manifest.excludeDirNames | ForEach-Object { $_.ToLower() })
$excludeFiles = @($manifest.excludeFilePatterns)

function Copy-Tree {
    param([string]$Source, [string]$Target)
    if (-not (Test-Path -LiteralPath $Target)) { New-Item -ItemType Directory -Path $Target -Force | Out-Null }
    foreach ($item in Get-ChildItem -LiteralPath $Source -Force) {
        if ($item.PSIsContainer) {
            if ($excludeDirs -contains $item.Name.ToLower()) { continue }
            Copy-Tree -Source $item.FullName -Target (Join-Path $Target $item.Name)
            continue
        }
        $skip = $false
        foreach ($pattern in $excludeFiles) { if ($item.Name -like $pattern) { $skip = $true; break } }
        if ($skip) { continue }
        Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $Target $item.Name) -Force
    }
}

Write-Host '============================================================='
Write-Host ' CampusSwap submission package builder'
Write-Host (' repo   : ' + $repo)
Write-Host (' staging: ' + $stage)
Write-Host '============================================================='

# --- 1. fresh staging tree ----------------------------------------------------
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null

Write-Host ''
Write-Host '[1/5] copying sources and documents (junk excluded by manifest) ...'
foreach ($entry in $manifest.include) {
    $src = Join-Path $repo $entry.from
    $dst = Join-Path $stage $entry.to
    if (-not (Test-Path -LiteralPath $src)) { Add-Problem ('missing include source: ' + $entry.from); continue }
    if ($entry.type -eq 'file') {
        Copy-Item -LiteralPath $src -Destination $dst -Force
        Write-Host ('    file  ' + $entry.from)
    } else {
        Copy-Tree -Source $src -Target $dst
        $n = @(Get-ChildItem -LiteralPath $dst -Recurse -File -Force).Count
        Write-Host ('    dir   ' + $entry.from + '  (' + $n + ' files)')
    }
}

# --- 2. entry guide -----------------------------------------------------------
Write-Host ''
Write-Host '[2/5] entry guide ...'
$guideSrc = Join-Path $repo $manifest.guideSource
if (-not (Test-Path -LiteralPath $guideSrc)) { Add-Problem ('guide missing: ' + $manifest.guideSource) }
else {
    Copy-Item -LiteralPath $guideSrc -Destination (Join-Path $stage $manifest.entryDoc) -Force
    Write-Host ('    ' + $manifest.entryDoc)
}

# --- 3. screenshots + gallery -------------------------------------------------
Write-Host ''
Write-Host '[3/5] screenshots and the offline gallery ...'
$shotsDst = Join-Path $stage $manifest.shotsDir
New-Item -ItemType Directory -Path $shotsDst -Force | Out-Null
$figures = @()
$i = 0
$missingShots = @()
foreach ($shot in $manifest.shots) {
    $i++
    $src = Join-Path $manifest.shotSourceDir $shot.file
    if (-not (Test-Path -LiteralPath $src)) { $missingShots += $shot.file; continue }
    Copy-Item -LiteralPath $src -Destination (Join-Path $shotsDst $shot.file) -Force
    $figures += @"
    <figure>
      <a href="$($manifest.shotsDir)/$($shot.file)" target="_blank" rel="noopener">
        <img loading="lazy" src="$($manifest.shotsDir)/$($shot.file)" alt="$($shot.caption)">
      </a>
      <figcaption><b><span class="idx">$i</span>$($shot.caption)</b></figcaption>
    </figure>
"@
}
if ($missingShots.Count -gt 0) { Add-Problem ('screenshots not found: ' + ($missingShots -join ', ')) }
$templatePath = Join-Path $repo $manifest.galleryTemplate
$gallery = [System.IO.File]::ReadAllText($templatePath, [System.Text.Encoding]::UTF8)
$gallery = $gallery.Replace('{{TITLE}}', $manifest.galleryTitle)
$gallery = $gallery.Replace('{{SUBTITLE}}', $manifest.gallerySubtitle)
$gallery = $gallery.Replace('{{FIGURES}}', ($figures -join "`n"))
$gallery = $gallery.Replace('{{COUNT}}', [string]$figures.Count)
$galleryPath = Join-Path $stage $manifest.galleryFile
[System.IO.File]::WriteAllText($galleryPath, $gallery, $Utf8NoBom)
Write-Host ('    ' + $manifest.galleryFile + '  (' + $figures.Count + ' figures, ' + (Format-Size (Get-Item $galleryPath).Length) + ')')

# --- 4. verification ----------------------------------------------------------
Write-Host ''
Write-Host '[4/5] verifying the tree ...'
$allFiles = @(Get-ChildItem -LiteralPath $stage -Recurse -File -Force)
$allDirs = @(Get-ChildItem -LiteralPath $stage -Recurse -Directory -Force)
$totalBytes = ($allFiles | Measure-Object Length -Sum).Sum

foreach ($d in $allDirs) {
    if ($excludeDirs -contains $d.Name.ToLower()) { Add-Problem ('excluded directory leaked into the package: ' + $d.FullName.Replace($stage, '')) }
}
foreach ($f in $allFiles) {
    foreach ($pattern in $excludeFiles) { if ($f.Name -like $pattern) { Add-Problem ('excluded file leaked: ' + $f.FullName.Replace($stage, '')) } }
}
foreach ($must in @('README.md', 'backend\pom.xml', 'backend\mvnw.cmd', 'backend\sql\schema.sql', 'backend\sql\data.sql',
                    'frontend\package.json', 'frontend\pnpm-lock.yaml', 'frontend\src\main.ts',
                    'docs\MASTER-PLAN.md', 'docs\03-qa-review\CODE_REVIEW.md', 'docs\02-design\UI-PREVIEW.html',
                    'docs\03-qa-review\run-all-checks.ps1')) {
    if (-not (Test-Path -LiteralPath (Join-Path $stage $must))) { Add-Problem ('required file missing in package: ' + $must) }
}
if ($totalBytes -gt $MaxBytes) { Add-Problem ('staging exceeds the 2 GB platform limit: ' + (Format-Size $totalBytes)) }
$hasFrontendSrc = Test-Path -LiteralPath (Join-Path $stage 'frontend\src\views')
if (-not $hasFrontendSrc) { Add-Problem 'frontend/src/views missing' }
# image references inside the gallery must resolve to files that really exist
$html = [System.IO.File]::ReadAllText($galleryPath, [System.Text.Encoding]::UTF8)
$refs = [regex]::Matches($html, 'src="(shots/[^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
$broken = @(); foreach ($rel in $refs) { if (-not (Test-Path -LiteralPath (Join-Path $stage ($rel -replace '/', '\')))) { $broken += $rel } }
if ($broken.Count -gt 0) { Add-Problem ('gallery references broken images: ' + ($broken -join ', ')) }
Write-Host ('    files=' + $allFiles.Count + '  dirs=' + $allDirs.Count + '  size=' + (Format-Size $totalBytes))
Write-Host ('    gallery image refs checked: ' + $refs.Count + ' (broken=' + $broken.Count + ')')

# --- 5. zip -------------------------------------------------------------------
$zipPath = ''
if (-not $NoZip) {
    Write-Host ''
    Write-Host '[5/5] building the zip with tar.exe (UTF-8 file names) ...'
    $stamp = Get-Date -Format 'yyyyMMdd'
    $zipPath = Join-Path $DistRoot ($rootName + '-' + $stamp + '.zip')
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    & tar.exe -a -c -f $zipPath -C $stagingParent $rootName
    if ($LASTEXITCODE -ne 0) { Add-Problem ('tar.exe failed with exit ' + $LASTEXITCODE) }
    elseif (-not (Test-Path -LiteralPath $zipPath)) { Add-Problem 'zip was not created' }
    else {
        $zipBytes = (Get-Item -LiteralPath $zipPath).Length
        if ($zipBytes -gt $MaxBytes) { Add-Problem ('zip exceeds the 2 GB platform limit: ' + (Format-Size $zipBytes)) }
        $entries = @(& tar.exe -tf $zipPath)
        Write-Host ('    ' + $zipPath)
        Write-Host ('    zip size=' + (Format-Size $zipBytes) + '  entries=' + $entries.Count)
        # Verify by EXTRACTING: tar's stdout goes through the console code page, so
        # comparing Chinese names from `tar -tf` gives false alarms (hit 2026-09-25).
        # The file system is UTF-16 end to end, so a round trip is the honest check.
        $probe = Join-Path $env:TEMP ('cs-zipcheck-' + (Get-Date -Format 'HHmmss'))
        if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Recurse -Force }
        New-Item -ItemType Directory -Path $probe -Force | Out-Null
        & tar.exe -xf $zipPath -C $probe
        $probeRoot = Join-Path $probe $rootName
        $mustExist = @($manifest.entryDoc, $manifest.galleryFile, (Join-Path $manifest.shotsDir $manifest.shots[0].file),
                       'backend\sql\schema.sql', 'frontend\package.json', 'docs\03-qa-review\CODE_REVIEW.md')
        $missing = @(); foreach ($rel in $mustExist) { if (-not (Test-Path -LiteralPath (Join-Path $probeRoot $rel))) { $missing += $rel } }
        if ($missing.Count -gt 0) { Add-Problem ('zip round-trip is missing: ' + ($missing -join ', ')) }
        $probeShots = @(Get-ChildItem -LiteralPath (Join-Path $probeRoot $manifest.shotsDir) -Filter '*.png' -ErrorAction SilentlyContinue).Count
        Write-Host ('    round-trip extract: root=' + (Test-Path -LiteralPath $probeRoot) + '  shots=' + $probeShots + '  missing=' + $missing.Count)
        Remove-Item -LiteralPath $probe -Recurse -Force
        if (-not $NoDesktopCopy -and (Test-Path -LiteralPath $DesktopDir)) {
            $desktopZip = Join-Path $DesktopDir ($rootName + '-' + $stamp + '.zip')
            Copy-Item -LiteralPath $zipPath -Destination $desktopZip -Force
            $a = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
            $b = (Get-FileHash -LiteralPath $desktopZip -Algorithm SHA256).Hash
            $flag = 'MISMATCH'; if ($a -eq $b) { $flag = 'SHA256 OK' }
            Write-Host ('    desktop copy: ' + $desktopZip + '  ' + $flag)
        }
    }
}

Write-Host ''
Write-Host '============================================================='
if ($problems.Count -eq 0) {
    Write-Host ' RESULT: OK - package built and verified'
    if ($zipPath) { Write-Host (' zip    : ' + $zipPath) }
    Write-Host (' staging: ' + $stage)
    Write-Host '============================================================='
    exit 0
}
Write-Host (' RESULT: FAILED - ' + $problems.Count + ' problem(s)')
foreach ($p in $problems) { Write-Host ('   - ' + $p) }
Write-Host '============================================================='
exit 1
