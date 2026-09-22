# verify-m4.ps1 -- M4 document-domain self-check (pure ASCII output only!)
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File verify-m4.ps1
# Re-runnable, static + cross-file consistency only (no server / DB needed).
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

$backend = Join-Path $RepoRoot 'backend'
$javaRoot = Join-Path $backend 'src\main\java\com\campusswap'
$docRoot = Join-Path $javaRoot 'document'
$sqlDir = Join-Path $backend 'sql'
$docsDir = Join-Path $RepoRoot 'docs'
$qaDir = Join-Path $docsDir '03-qa-review'

function Read-All {
    param([string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}
function Java-Files {
    param([string]$Root)
    return @(Get-ChildItem -LiteralPath $Root -Recurse -Filter '*.java' -File)
}

Write-Host '============================================================='
Write-Host ' CampusSwap M4 self-check (document domain)'
Write-Host (" RepoRoot = " + $RepoRoot)
Write-Host '============================================================='

$allDoc = ''
foreach ($f in (Java-Files $docRoot)) { $allDoc += (Read-All $f.FullName) }
$allJava = ''
foreach ($f in (Java-Files $javaRoot)) { $allJava += (Read-All $f.FullName) }

# --- D1: controllers and endpoint inventory ---------------------------------
$ctrlFiles = @(Get-ChildItem -LiteralPath (Join-Path $docRoot 'controller') -Filter '*Controller.java' -File)
$endpoints = @()
$permCount = 0
foreach ($f in $ctrlFiles) {
    $c = Read-All $f.FullName
    $permCount += ([regex]::Matches($c, '@RequiresPermission\(')).Count
    $classPath = [regex]::Match($c, '@RequestMapping\("([^"]+)"\)').Groups[1].Value
    # path argument is optional (bare @GetMapping/@PostMapping) -> fall back to the class prefix
    foreach ($m in [regex]::Matches($c, '@(Get|Post|Put|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\))?')) {
        $endpoints += ($m.Groups[1].Value.ToUpper() + ' ' + $classPath + $m.Groups[2].Value)
    }
}
Check 'D1a 6 document controllers' ($ctrlFiles.Count -eq 6) ('count=' + $ctrlFiles.Count)
Check 'D1b 30 document endpoints' ($endpoints.Count -eq 30) ('count=' + $endpoints.Count)
Check 'D1c 30 guarded endpoints (@RequiresPermission)' ($permCount -eq 30) ('count=' + $permCount)

$spec = Read-All (Join-Path $docsDir '02-design\API_SPECIFICATION.md')
$notInSpec = @()
foreach ($e in $endpoints) {
    $path = $e.Substring($e.IndexOf(' ') + 1)
    if ($spec -notlike ('*' + $path + '*')) { $notInSpec += $e }
}
Check 'D1d all document endpoints documented in API_SPECIFICATION' ($notInSpec.Count -eq 0) ('missing=' + ($notInSpec -join ','))

# --- D2: permission codes exist in seed data --------------------------------
$data = Read-All (Join-Path $sqlDir 'data.sql')
$usedCodes = @([regex]::Matches($allDoc, '@RequiresPermission\("([a-z:]+)"\)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$unknown = @($usedCodes | Where-Object { $data -notlike ("*'" + $_ + "'*") })
Check 'D2 all document permission codes exist in sql/data.sql' ($unknown.Count -eq 0) `
    ('codes=' + $usedCodes.Count + ' unknown=' + ($unknown -join ','))

# --- D3: list VO must not carry the big text column -------------------------
$docVo = Read-All (Join-Path $docRoot 'vo\DocumentVo.java')
$detailVo = Read-All (Join-Path $docRoot 'vo\DocumentDetailVo.java')
Check 'D3a DocumentVo has no contentMd (list must not read big text)' `
    (($docVo -notlike '*private String contentMd*') -and ($detailVo -like '*private String contentMd*')) 'DocumentVo/DocumentDetailVo'

# --- D4: list queries use DTO projection, never entity select ---------------
$queryImpl = Read-All (Join-Path $docRoot 'repository\DocumentQueryRepositoryImpl.java')
Check 'D4a list query uses Criteria + construct projection' `
    (($queryImpl -like '*cb.construct(DocumentListRow.class*') -and ($queryImpl -like '*multiselect*' -or $queryImpl -like '*criteriaBuilder.construct*' -or $queryImpl -like '*cb.construct*')) `
    'DocumentQueryRepositoryImpl'
Check 'D4b list query skips count query on last page (PageableExecutionUtils)' `
    ($queryImpl -like '*PageableExecutionUtils.getPage*') 'DocumentQueryRepositoryImpl'
$row = Read-All (Join-Path $docRoot 'repository\DocumentListRow.java')
Check 'D4c projection row carries no contentMd' ($row -notlike '*contentMd*') 'DocumentListRow'

# --- D5: native paths for trash / favorites ---------------------------------
$docRepo = Read-All (Join-Path $docRoot 'repository\DocumentRepository.java')
Check 'D5a trash query filters deleted = 1 natively' `
    (($docRepo -like '*findTrashPage*') -and ($docRepo -like '*d.deleted = 1*') -and ($docRepo -like '*nativeQuery = true*')) 'DocumentRepository'
Check 'D5b favorites query joins doc_favorite natively' `
    (($docRepo -like '*findFavoritePage*') -and ($docRepo -like '*JOIN doc_favorite*')) 'DocumentRepository'
Check 'D5c destroy cascade cleans versions, favorites, tag rels' `
    ($allDoc -like '*deleteByDocumentIdPhysically*' -and $allDoc -like '*deleteByDocumentId*') 'document repositories'

# --- D6: column mapping is pinned in one place ------------------------------
$cols = Read-All (Join-Path $docRoot 'repository\DocumentColumns.java')
$colCount = ([regex]::Matches($cols, '(?m)^\s*public static final int [A-Z_]+ = \d+;')).Count
Check 'D6a DocumentColumns pins 16 column indexes' ($colCount -eq 16) ('count=' + $colCount)
$idxs = @([regex]::Matches($cols, '(?m)public static final int [A-Z_]+ = (\d+);') | ForEach-Object { [int]$_.Groups[1].Value })
$distinct = @($idxs | Sort-Object -Unique)
Check 'D6b indexes are 0..15 without gaps' (($distinct.Count -eq 16) -and ($distinct[0] -eq 0) -and ($distinct[15] -eq 15)) ('distinct=' + $distinct.Count)

# --- D7: soft delete releases unique key (rule 18) --------------------------
$tag = Read-All (Join-Path $javaRoot 'entity\Tag.java')
Check 'D7 doc_tag @SQLDelete rewrites name to release the unique key' `
    (($tag -like '*@SQLDelete*') -and ($tag -like "*CONCAT(LEFT(name, 30), '#del#', id)*")) 'entity/Tag.java'

# --- D8: forbidden patterns still zero -------------------------------------
$eager = ([regex]::Matches($allJava, 'FetchType\.EAGER')).Count
$dataAnn = ([regex]::Matches($allJava, '(?m)^\s*@Data\s*$')).Count
Check 'D8a zero EAGER / zero @Data in the whole backend' (($eager -eq 0) -and ($dataAnn -eq 0)) `
    ('eager=' + $eager + ' data=' + $dataAnn)
$badFetch = @()
foreach ($f in (Java-Files (Join-Path $docRoot 'repository'))) {
    # code lines only: javadoc explaining the JOIN FETCH ban is not a violation
    $code = @(Get-Content -LiteralPath $f.FullName | Where-Object { $_ -notmatch '^\s*(\*|//|/\*)' }) -join "`n"
    foreach ($m in [regex]::Matches($code, 'join fetch\s+([A-Za-z0-9_.]+)')) {
        # to-one fetch on Document.category is the documented approach; collection fetch breaks paging
        if ($m.Groups[1].Value -notmatch '\.category$') { $badFetch += ($f.Name + ':' + $m.Groups[1].Value) }
    }
}
Check 'D8b no collection JOIN FETCH in document repositories' ($badFetch.Count -eq 0) ('violations=' + ($badFetch -join ','))

# --- D9: service hygiene ----------------------------------------------------
$svcImpl = @(Get-ChildItem -LiteralPath (Join-Path $docRoot 'service\impl') -Filter '*.java' -File)
$unpaged = @($svcImpl | Where-Object { (Read-All $_.FullName) -like '*findAll()*' })
Check 'D9a no unpaged findAll() in document services' ($unpaged.Count -eq 0) ('files=' + (($unpaged | ForEach-Object { $_.Name }) -join ','))
$svcAll = ''
foreach ($f in $svcImpl) { $svcAll += (Read-All $f.FullName) }
Check 'D9b stats uses one aggregate SQL, not three counts()' `
    (($svcAll -notlike '*countByStatus(*') -and ($docRepo -like '*statOverview*')) 'StatController + DocumentRepository'
Check 'D9c view dedup goes through RedisKeys (no hardcoded view:doc literal)' `
    (($svcAll -like '*RedisKeys.viewDedup*') -and ($allDoc -notlike '*"view:doc:"*')) 'DocumentServiceImpl'

# --- D10: upload whitelist --------------------------------------------------
$fileSvc = Read-All (Join-Path $docRoot 'service\impl\FileStorageService.java')
Check 'D10a upload checks extension + mime + size' `
    (($fileSvc -like '*ALLOWED*') -and ($fileSvc -like '*getContentType*') -and ($fileSvc -like '*MAX_SIZE*')) 'FileStorageService'
Check 'D10b upload stores under uploads/yyyy/MM with UUID name' `
    (($fileSvc -like '*yyyy/MM*') -and ($fileSvc -like '*UUID.randomUUID*')) 'FileStorageService'

# --- D11: DTO/VO names align with GLOSSARY 3.7 ------------------------------
$glossary = Read-All (Join-Path $docsDir '01-requirements\GLOSSARY.md')
$names = @('DocumentCreateDtoReq', 'DocumentUpdateDtoReq', 'DocumentSearchDtoReq', 'DocumentMineDtoReq',
    'DocumentAuditDtoReq', 'DocumentRejectDtoReq', 'DocumentDeriveDtoReq', 'DocumentDestroyDtoReq',
    'ReviewPageDtoReq', 'FavoritePageDtoReq', 'CategoryCreateDtoReq', 'CategoryUpdateDtoReq',
    'TagCreateDtoReq', 'TagUpdateDtoReq', 'TagPageDtoReq', 'DocumentVo', 'DocumentDetailVo',
    'DocumentVersionVo', 'CategoryVo', 'TagVo', 'FavoriteVo', 'ImageVo', 'StatVo')
$missingName = @()
foreach ($n in $names) {
    $file = Get-ChildItem -LiteralPath $docRoot -Recurse -Filter ($n + '.java') -File
    if ($file.Count -eq 0) { $missingName += $n; continue }
    if ($n -like '*DtoReq' -and $glossary -notlike ('*' + $n + '*')) { $missingName += ($n + ':not-in-glossary') }
}
Check 'D11 23 document DTO/VO classes exist and are in GLOSSARY' ($missingName.Count -eq 0) ('issues=' + ($missingName -join ','))

# --- D12: state machine coverage -------------------------------------------
$docSvc = Read-All (Join-Path $docRoot 'service\impl\DocumentServiceImpl.java')
$reviewSvc = Read-All (Join-Path $docRoot 'service\impl\ReviewServiceImpl.java')
$states = @('DocumentStatus.DRAFT', 'DocumentStatus.PUBLISHED', 'DocumentStatus.ARCHIVED', 'DocumentStatus.TRASH')
$missState = @($states | Where-Object { $allDoc -notlike ('*' + $_ + '*') })
Check 'D12a all four document states handled' ($missState.Count -eq 0) ('missing=' + ($missState -join ','))
$types = @('ChangeType.CREATE', 'ChangeType.EDIT', 'ChangeType.PUBLISH', 'ChangeType.AUDIT', 'ChangeType.REJECT',
    'ChangeType.ARCHIVE', 'ChangeType.RESTORE', 'ChangeType.DELETE')
$missType = @($types | Where-Object { $allDoc -notlike ('*' + $_ + '*') })
Check 'D12b all eight version snapshot types written' ($missType.Count -eq 0) ('missing=' + ($missType -join ','))

# --- D13: QA artifacts ------------------------------------------------------
$checklist = Join-Path $qaDir 'TEST_CHECKLIST.md'
Check 'D13a TEST_CHECKLIST.md exists' (Test-Path -LiteralPath $checklist) 'docs/03-qa-review/TEST_CHECKLIST.md'
if (Test-Path -LiteralPath $checklist) {
    $cl = Read-All $checklist
    $acs = @('AC-02.1', 'AC-02.2', 'AC-02.3', 'AC-03.1', 'AC-03.2', 'AC-03.3', 'AC-04.1', 'AC-04.2', 'AC-04.3',
        'AC-05.1', 'AC-05.2', 'AC-05.3', 'AC-06.1', 'AC-06.2', 'AC-06.3', 'AC-07.1', 'AC-07.2', 'AC-07.3',
        'AC-08.1', 'AC-08.2', 'AC-08.3')
    $missAc = @($acs | Where-Object { $cl -notlike ('*' + $_ + '*') })
    Check 'D13b checklist covers AC-02.1 .. AC-08.3 (21 assertions)' ($missAc.Count -eq 0) ('missing=' + ($missAc -join ','))
    $passRows = ([regex]::Matches($cl, '(?m)^\| AC-')).Count
    Check 'D13c checklist has one row per assertion' ($passRows -ge 21) ('rows=' + $passRows)
}
$scripts = @('verify-m4.ps1', 'verify-m4-http.ps1')
$nonAscii = @()
foreach ($s in $scripts) {
    $p = Join-Path $qaDir $s
    if (Test-Path -LiteralPath $p) {
        $bytes = [System.IO.File]::ReadAllBytes($p)
        if (@($bytes | Where-Object { $_ -gt 127 }).Count -gt 0) { $nonAscii += $s }
    }
}
Check 'D13d M4 QA scripts are pure ASCII' ($nonAscii.Count -eq 0) ('nonAscii=' + ($nonAscii -join ','))

# --- D14: MASTER-PLAN M4 tasks ticked ---------------------------------------
$plan = Read-All (Join-Path $docsDir 'MASTER-PLAN.md')
$m4 = [regex]::Match($plan, '(?s)### M4 .*?(?=### M5)').Value
$open = @([regex]::Matches($m4, '(?m)^- \[ \] \*\*(T4\.\d+)\*\*') | ForEach-Object { $_.Groups[1].Value })
Check 'D14 all M4 tasks ticked in MASTER-PLAN.md' ($open.Count -eq 0) ('open=' + ($open -join ','))

Write-Host ''
Write-Host '============================================================='
Write-Host (" RESULT: PASS=" + $script:pass + "  FAIL=" + $script:fails)
Write-Host '============================================================='
exit $script:fails
