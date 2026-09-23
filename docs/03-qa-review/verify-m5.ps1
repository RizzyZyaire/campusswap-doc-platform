# verify-m5.ps1 -- M5 frontend self-check (pure ASCII output only!)
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File verify-m5.ps1
# Re-runnable, static + cross-file consistency only (no server / DB / node needed).
# NOTE: keep this file pure ASCII -- PS 5.1 reads BOM-less UTF-8 as ANSI and CJK literals break the parser.

param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fails = 0

function Check {
    param([string]$Nm, [bool]$Ok, [string]$Detail)
    if ($Ok) { $script:pass++; Write-Host ("[PASS] {0} -- {1}" -f $Nm, $Detail) }
    else { $script:fails++; Write-Host ("[FAIL] {0} -- {1}" -f $Nm, $Detail) -ForegroundColor Red }
}

$frontend = Join-Path $RepoRoot 'frontend'
$srcDir = Join-Path $frontend 'src'
$viewsDir = Join-Path $srcDir 'views'
$apiDir = Join-Path $srcDir 'api'
$stylesDir = Join-Path $srcDir 'styles'
$docsDir = Join-Path $RepoRoot 'docs'
$qaDir = Join-Path $docsDir '03-qa-review'
$backend = Join-Path $RepoRoot 'backend'
$javaRoot = Join-Path $backend 'src\main\java\com\campusswap'

function Read-All {
    param([string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}
function Src-Files {
    param([string]$Ext)
    return @(Get-ChildItem -LiteralPath $srcDir -Recurse -File | Where-Object { $_.Extension -eq $Ext })
}

Write-Host '============================================================='
Write-Host ' CampusSwap M5 self-check (frontend implementation)'
Write-Host (" RepoRoot = " + $RepoRoot)
Write-Host '============================================================='

# --- D1: every view is real (no placeholder stub left) ----------------------
$vueFiles = Src-Files '.vue'
$viewFiles = @(Get-ChildItem -LiteralPath $viewsDir -Recurse -Filter '*.vue' -File)
$stubs = @()
foreach ($f in $viewFiles) {
    $c = Read-All $f.FullName
    # a stub is a file that still delegates to the scaffold component; small files (e.g. the 404 page) are fine
    if ($c -like '*PagePlaceholder*') { $stubs += $f.Name }
}
Check 'D1a 15 view files implemented' ($viewFiles.Count -eq 15) ('count=' + $viewFiles.Count)
Check 'D1b no view left as a placeholder stub' ($stubs.Count -eq 0) ('stubs=' + ($stubs -join ','))
$noSetup = @()
foreach ($f in $viewFiles) {
    $c = Read-All $f.FullName
    if ($c -notlike '*<script setup lang="ts">*') { $noSetup += $f.Name }
}
Check 'D1c every view uses script setup with lang=ts' ($noSetup.Count -eq 0) ('missing=' + ($noSetup -join ','))

# --- D2: router coverage (UI_UX_SPECIFICATION 6.1) -------------------------
$router = Read-All (Join-Path $srcDir 'router\index.ts')
$routes = @()
foreach ($m in [regex]::Matches($router, "path:\s*'([^']+)'[^}]*?name:\s*'([^']+)'[^}]*?import\('@/([^']+)'\)")) {
    $routes += ($m.Groups[2].Value + '|' + $m.Groups[1].Value + '|' + $m.Groups[3].Value)
}
Check 'D2a 15 named routes in router (13 functional + 2 error)' ($routes.Count -eq 15) ('count=' + $routes.Count)
$wantRoutes = @(
    'login|/login|views/auth/LoginView.vue',
    'workbench|/workbench|views/workbench/WorkbenchView.vue',
    'docs|/docs|views/document/DocumentListView.vue',
    'docs-edit|/docs/edit/:id?|views/document/DocumentEditView.vue',
    'docs-detail|/docs/:id|views/document/DocumentDetailView.vue',
    'my|/my|views/document/MyDocumentView.vue',
    'review|/review|views/review/ReviewView.vue',
    'governance|/governance|views/admin/GovernanceView.vue',
    'taxonomy|/taxonomy|views/admin/TaxonomyView.vue',
    'admin-users|/admin/users|views/admin/UserAdminView.vue',
    'admin-roles|/admin/roles|views/admin/RoleAdminView.vue',
    'admin-org|/admin/org|views/admin/OrgAdminView.vue',
    'me|/me|views/me/ProfileView.vue',
    'forbidden|/403|views/error/ForbiddenView.vue',
    'not-found|/404|views/error/NotFoundView.vue'
)
$missingRoutes = @($wantRoutes | Where-Object { $routes -notcontains $_ })
Check 'D2b spec routes match name/path/view file' ($missingRoutes.Count -eq 0) ('missing=' + ($missingRoutes -join ' ; '))
$iEdit = $router.IndexOf("path: '/docs/edit/:id?'")
$iDetail = $router.IndexOf("path: '/docs/:id'")
Check 'D2c /docs/edit/:id? is declared before /docs/:id' (($iEdit -gt 0) -and ($iDetail -gt 0) -and ($iEdit -lt $iDetail)) `
    ('editAt=' + $iEdit + ' detailAt=' + $iDetail)
$fileRoutes = @($viewFiles | ForEach-Object { $_.FullName.Substring($viewsDir.Length + 1).Replace('\', '/') })
$notRouted = @($fileRoutes | Where-Object { $router -notlike ('*' + $_ + '*') })
Check 'D2d every view file is imported by the router' ($notRouted.Count -eq 0) ('unrouted=' + ($notRouted -join ','))

# --- D3: 6 themes wired end to end -----------------------------------------
$themeCss = Read-All (Join-Path $stylesDir 'theme.css')
$themes = @('hebtu', 'gingko', 'celadon', 'ink', 'jiang', 'night')
$themeHits = 0
foreach ($t in $themes) { if ($themeCss.Contains('html[data-theme="' + $t + '"]')) { $themeHits++ } }
Check 'D3a theme.css defines all 6 themes' ($themeHits -eq 6) ('themes=' + $themeHits)
$themeStore = Read-All (Join-Path $srcDir 'stores\theme.ts')
$storeHits = 0
foreach ($t in $themes) { if ($themeStore -like ("*'" + $t + "'*")) { $storeHits++ } }
Check 'D3b theme store lists the same 6 themes' ($storeHits -eq 6) ('themes=' + $storeHits)
$indexHtml = Read-All (Join-Path $frontend 'index.html')
Check 'D3c index.html carries data-theme on <html>' ($indexHtml -like '*data-theme=*') 'index.html'

# --- D4: coding red lines (no any / no inline style) -----------------------
$anyHits = @()
$styleHits = @()
foreach ($f in (Src-Files '.ts') + (Src-Files '.vue')) {
    $c = Read-All $f.FullName
    $rel = $f.FullName.Substring($srcDir.Length + 1)
    if ([regex]::IsMatch($c, '(:\s*any\b)|(<any>)|(as\s+any\b)|(any\[\])')) { $anyHits += $rel }
    if ([regex]::IsMatch($c, '(\sstyle=")|(v-bind:style)|(:style=")')) { $styleHits += $rel }
}
Check 'D4a no TS "any" in src' ($anyHits.Count -eq 0) ('files=' + ($anyHits -join ','))
Check 'D4b no inline style attribute / v-bind:style' ($styleHits.Count -eq 0) ('files=' + ($styleHits -join ','))

# --- D5: stale-form contract (versionNum) ----------------------------------
$docTypes = Read-All (Join-Path $srcDir 'types\document.ts')
Check 'D5a DocumentUpdateDtoReq carries versionNum' `
    (($docTypes -like '*interface DocumentUpdateDtoReq*') -and ($docTypes -like '*versionNum: number*')) 'types/document.ts'
$editView = Read-All (Join-Path $viewsDir 'document\DocumentEditView.vue')
Check 'D5b edit view sends versionNum on PUT' `
    (($editView -like '*versionNum: versionNum.value*') -or ($editView -like '*versionNum.value,*')) 'DocumentEditView.vue'
Check 'D5c edit view renders the 409 conflict bar with a refresh action' `
    (($editView -like '*conflict-bar*') -and ($editView -like '*conflict.value*')) 'DocumentEditView.vue'
Check 'D5d edit view keeps status conflict (readonly) apart from version conflict' `
    (($editView -like '*readOnlyReason*') -and ($editView -like '*isConflict*')) 'DocumentEditView.vue'
$mainCss = Read-All (Join-Path $stylesDir 'main.css')
Check 'D5e .conflict-bar style exists in main.css' ($mainCss -like '*conflict-bar*') 'styles/main.css'

# --- D6: doc:offline must not exist anywhere in the client -----------------
$docApi = Read-All (Join-Path $apiDir 'documents.ts')
Check 'D6a api layer has no offline endpoint' `
    (($docApi -notlike '*/offline*') -and ($docApi -notlike '*offline(id*')) 'api/documents.ts'
Check 'D6b api layer documents why offline is missing' ($docApi -like '*10.6*') 'api/documents.ts'
$offlineHits = @()
foreach ($f in (Src-Files '.ts') + (Src-Files '.vue')) {
    $c = Read-All $f.FullName
    if ($c -like "*'/offline'*") { $offlineHits += $f.Name }
}
Check 'D6c no view calls an offline endpoint' ($offlineHits.Count -eq 0) ('files=' + ($offlineHits -join ','))

# --- D7: the 39 permission codes agree with the seed data ------------------
$permTs = Read-All (Join-Path $srcDir 'utils\perm.ts')
$feCodes = @([regex]::Matches($permTs, "'((?:doc|sys):[a-z:]+)'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$data = Read-All (Join-Path $backend 'sql\data.sql')
$dbCodes = @([regex]::Matches($data, "'((?:doc|sys):[a-z:]+)'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
Check 'D7a 39 permission codes in utils/perm.ts' ($feCodes.Count -eq 39) ('count=' + $feCodes.Count)
Check 'D7b 39 permission codes in sql/data.sql' ($dbCodes.Count -eq 39) ('count=' + $dbCodes.Count)
$codeDiff = @(Compare-Object $feCodes $dbCodes)
Check 'D7c frontend permission codes == database seed codes' ($codeDiff.Count -eq 0) ('diff=' + $codeDiff.Count)
$labelCodes = @([regex]::Matches($permTs, "'((?:doc|sys):[a-z:]+)':") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
Check 'D7d PERM_LABEL covers all 39 codes' ($labelCodes.Count -eq 39) ('count=' + $labelCodes.Count)

# --- D8: token storage has a single source ---------------------------------
$reqTs = Read-All (Join-Path $apiDir 'request.ts')
Check 'D8a TOKEN_KEY is campusswap.token' ($reqTs -like "*'campusswap.token'*") 'api/request.ts'
$userStore = Read-All (Join-Path $srcDir 'stores\user.ts')
Check 'D8b user store imports TOKEN_KEY instead of hardcoding' `
    (($userStore -like '*import { TOKEN_KEY }*') -and ($userStore -notlike "*'campusswap.token'*")) 'stores/user.ts'

# --- D9: every backend endpoint is reachable from src/api ------------------
$ctrlFiles = @(Get-ChildItem -LiteralPath $javaRoot -Recurse -Filter '*Controller.java' -File)
$beOps = @()
$bePaths = @()
foreach ($f in $ctrlFiles) {
    $c = Read-All $f.FullName
    $classPath = [regex]::Match($c, '@RequestMapping\("([^"]+)"\)').Groups[1].Value
    foreach ($m in [regex]::Matches($c, '@(Get|Post|Put|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\))?')) {
        $p = ($classPath + $m.Groups[2].Value)
        if ($p.StartsWith('/api')) { $p = $p.Substring(4) }
        $p = [regex]::Replace($p, '\{[^}]+\}', '{}')
        # count method+path pairs: GET/PUT/DELETE on the same path are three endpoints, not one
        $beOps += ($m.Groups[1].Value.ToUpper() + ' ' + $p)
        $bePaths += $p
    }
}
$beOps = @($beOps | Sort-Object -Unique)
$bePaths = @($bePaths | Sort-Object -Unique)
$fePaths = @()
foreach ($f in (Get-ChildItem -LiteralPath $apiDir -Filter '*.ts' -File)) {
    $c = Read-All $f.FullName
    foreach ($m in [regex]::Matches($c, "['`"``](/[A-Za-z0-9_\-/\`$\{\}\.]+)['`"``]")) {
        $p = $m.Groups[1].Value
        $p = [regex]::Replace($p, '\$\{[^}]+\}', '{}')
        $fePaths += $p
    }
}
$fePaths = @($fePaths | Sort-Object -Unique)
$uncovered = @()
foreach ($p in $bePaths) {
    if ($fePaths -notcontains $p) { $uncovered += $p }
}
Check 'D9a 57 backend endpoints inventoried (method+path)' ($beOps.Count -eq 57) ('count=' + $beOps.Count)
Check 'D9b every backend endpoint path is called from src/api' ($uncovered.Count -eq 0) ('uncovered=' + ($uncovered -join ','))

# --- D10: generated styles stay generated ----------------------------------
$compCss = Read-All (Join-Path $stylesDir 'components.css')
Check 'D10a components.css declares @layer components' ($compCss -like '*@layer components*') 'styles/components.css'
Check 'D10b components.css carries the do-not-edit banner' `
    (($compCss -like '*extract-preview.mjs*') -and ($compCss -like '*sync-preview*')) 'styles/components.css'
Check 'D10c theme.css carries the do-not-edit banner' ($themeCss -like '*extract-preview.mjs*') 'styles/theme.css'
Check 'D10d main.css is hand-written (not generated)' ($mainCss -like '*__HANDBOOK__*') 'styles/main.css'
Check 'D10e generated CSS has no orphan keyframe frames at layer top level' `
    (($compCss -notlike "`n  0%,100%*") -and ($compCss -notlike "`n  50%*")) 'styles/components.css'
Check 'D10f keyframes block survived extraction' ($compCss -like '*@keyframes sk{*') 'styles/components.css'

# --- D11: preview extraction tooling ---------------------------------------
$pkg = Read-All (Join-Path $frontend 'package.json')
Check 'D11a package.json exposes sync-preview / shot / check-classes' `
    (($pkg -like '*sync-preview*') -and ($pkg -like '*check-classes*') -and ($pkg -like '*shot.mjs*')) 'package.json'
Check 'D11b stale assets script is gone' ($pkg -notlike '*extract-assets.mjs*') 'package.json'
$manifestPath = Join-Path $srcDir 'assets\manifest.json'
Check 'D11c asset manifest exists' (Test-Path -LiteralPath $manifestPath) 'src/assets/manifest.json'
if (Test-Path -LiteralPath $manifestPath) {
    $man = Read-All $manifestPath
    $missingAssets = @()
    foreach ($m in [regex]::Matches($man, '"path":\s*"([^"]+)"')) {
        $rel = $m.Groups[1].Value.Replace('/', '\')
        if (-not (Test-Path -LiteralPath (Join-Path $srcDir $rel))) { $missingAssets += $rel }
    }
    Check 'D11d every manifest asset exists on disk' ($missingAssets.Count -eq 0) ('missing=' + ($missingAssets -join ','))
}

# --- D12: route-level permission guards ------------------------------------
$guardPairs = @(
    'docs|PERM.docSearch', 'my|PERM.docMine', 'review|PERM.docReview', 'governance|PERM.docManage',
    'taxonomy|PERM.docCategory', 'admin-users|PERM.sysUser', 'admin-roles|PERM.sysRole', 'admin-org|PERM.sysDept'
)
$guardMiss = @()
foreach ($p in $guardPairs) {
    $parts = $p.Split('|')
    if ($router -notlike ("*name: '" + $parts[0] + "'*" + $parts[1] + "*")) { $guardMiss += $p }
}
Check 'D12a guarded routes declare meta.perm' ($guardMiss.Count -eq 0) ('missing=' + ($guardMiss -join ','))
Check 'D12b router guard redirects to /403 when a permission is missing' ($router -like '*forbidden*') 'router/index.ts'

# --- D13: types come from GLOSSARY (no invented field names) ---------------
$glossary = Read-All (Join-Path $docsDir '01-requirements\GLOSSARY.md')
$invented = @()
foreach ($f in (Get-ChildItem -LiteralPath (Join-Path $srcDir 'types') -Filter '*.ts' -File)) {
    $c = Read-All $f.FullName
    foreach ($m in [regex]::Matches($c, '(?m)^\s{2}([a-z][A-Za-z0-9]*)\??:\s')) {
        $name = $m.Groups[1].Value
        if ($glossary -notlike ('*' + $name + '*')) { $invented += ($f.Name + ':' + $name) }
    }
}
Check 'D13 no invented field names in src/types' ($invented.Count -eq 0) ('invented=' + ($invented -join ','))

# --- D14: MASTER-PLAN M5 tasks ticked --------------------------------------
$plan = Read-All (Join-Path $docsDir 'MASTER-PLAN.md')
$m5 = [regex]::Match($plan, '(?s)### M5 .*?(?=### M6)').Value
$openTasks = @([regex]::Matches($m5, '(?m)^- \[ \] \*\*(T5\.\d+)\*\*') | ForEach-Object { $_.Groups[1].Value })
Check 'D14 all M5 tasks ticked in MASTER-PLAN.md' ($openTasks.Count -eq 0) ('open=' + ($openTasks -join ','))

# --- D15: QA scripts stay pure ASCII --------------------------------------
$scripts = @('verify-m5.ps1')
$nonAscii = @()
foreach ($s in $scripts) {
    $p = Join-Path $qaDir $s
    if (Test-Path -LiteralPath $p) {
        $bytes = [System.IO.File]::ReadAllBytes($p)
        if (@($bytes | Where-Object { $_ -gt 127 }).Count -gt 0) { $nonAscii += $s }
    }
}
Check 'D15a M5 QA script is pure ASCII' ($nonAscii.Count -eq 0) ('nonAscii=' + ($nonAscii -join ','))

Write-Host ''
Write-Host '============================================================='
Write-Host (" RESULT: PASS=" + $script:pass + "  FAIL=" + $script:fails)
Write-Host '============================================================='
exit $script:fails
