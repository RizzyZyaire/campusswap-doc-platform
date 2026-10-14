# =============================================================================
#  CampusSwap M6 static acceptance check (tests + code review + evidence chain)
#
#  Source is pure ASCII on purpose (Windows PowerShell 5.1 reads BOM-less .ps1
#  as ANSI, so any CJK in this file would break the parser). Document checks look
#  for ASCII anchors that the M6 documents deliberately carry:
#     TEST_CHECKLIST.md ....... <!-- M6-SQL-COUNTS --> / <!-- M6-EXCEPTION-PATHS -->
#     EXPLAIN-NOTES.md ........ <!-- M6-INDEX-REGRESSION -->
#     CODE_REVIEW.md .......... <!-- M6-CODE-REVIEW --> + R1..R6 DDL1..DDL6 L1..L5 F1..F4
#     M6-CLOSURE.md ........... <!-- M6-CLOSURE -->
#
#  Usage: powershell -NoProfile -ExecutionPolicy Bypass -File verify-m6.ps1
#  Exit code = number of failed checks (0 = all pass)
# =============================================================================
param(
    [string]$Repo = 'D:\DevEnv\projects\campusswap',
    [string]$PerfJson = 'D:\DevEnv\logs\sql-counts-perf.json',
    [string]$SeedJson = 'D:\DevEnv\logs\sql-counts-seed.json'
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Check {
    param([string]$Label, $Actual, $Expected)
    if ($Actual -eq $Expected) { $script:Pass++; Write-Host ('  [PASS] ' + $Label + ' = ' + $Actual) }
    else {
        $script:Fail++; $script:Failures += $Label
        Write-Host ('  [FAIL] ' + $Label + ' : expected [' + $Expected + '] actual [' + $Actual + ']')
    }
}

function Check-True {
    param([string]$Label, $Condition, [string]$Detail = '')
    if ($Condition) { $script:Pass++; Write-Host ('  [PASS] ' + $Label + ' ' + $Detail) }
    else { $script:Fail++; $script:Failures += $Label; Write-Host ('  [FAIL] ' + $Label + ' ' + $Detail) }
}

function Read-Text {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Count-In {
    param([string[]]$Files, [string]$Pattern)
    $n = 0
    foreach ($f in $Files) {
        $text = Read-Text $f
        $n += ([regex]::Matches($text, $Pattern)).Count
    }
    return $n
}

$MainJava = Join-Path $Repo 'backend\src\main\java'
$TestJava = Join-Path $Repo 'backend\src\test\java'
$FeSrc = Join-Path $Repo 'frontend\src'
$Qa = Join-Path $Repo 'docs\03-qa-review'
$MainFiles = @(Get-ChildItem -Path $MainJava -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$TestFiles = @(Get-ChildItem -Path $TestJava -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$FeFiles = @(Get-ChildItem -Path $FeSrc -Recurse -Include '*.ts', '*.vue' | ForEach-Object { $_.FullName })
$SqlFiles = @(Get-ChildItem -Path (Join-Path $Repo 'backend\sql') -Filter '*.sql' | ForEach-Object { $_.FullName })

Write-Host '============================================================='
Write-Host ' CampusSwap M6 static check (T6.1/T6.2/T6.4/T6.5/T6.6/T6.7)'
Write-Host '============================================================='

# ---------------------------------------------------------------- A. unit tests
Write-Host ''
Write-Host 'A. T6.1/T6.2 service-layer unit tests mapped 1:1 to the BDD assertions'
$acDisplay = Count-In $TestFiles '@DisplayName\("AC-0[1-8]\.[1-3]'
Check 'A1.bdd-unit-tests' $acDisplay 24
$expectedIds = @()
foreach ($story in 1..8) { foreach ($n in 1..3) { $expectedIds += ('AC-0' + $story + '.' + $n) } }
$dupOrMissing = @()
foreach ($id in $expectedIds) {
    $c = Count-In $TestFiles ('@DisplayName\("' + [regex]::Escape($id))
    if ($c -ne 1) { $dupOrMissing += ($id + '=' + $c) }
}
Check 'A2.each-ac-exactly-once' $dupOrMissing.Count 0
if ($dupOrMissing.Count -gt 0) { Write-Host ('      offenders: ' + ($dupOrMissing -join ', ')) }
$acTestFiles = @($TestFiles | Where-Object { (Split-Path $_ -Leaf) -like 'Ac*Test.java' })
Check-True 'A3.ac-test-classes' ($acTestFiles.Count -ge 8) ('files=' + $acTestFiles.Count)
# A4 -- whitelist form (rewritten 2026-10-15).
#
# The old assertion was "no @SpringBootTest anywhere under Ac*Test.java", which
# was correct while the whole BDD suite was pure Mockito. Later milestones added
# integration tests that genuinely need a Spring context, so the old wording
# produced a permanent, meaningless [FAIL] on every run.
#
# A Spring context is allowed ONLY for the classes listed below; each entry
# records why a hand-rolled mock would be the wrong tool there:
#   SearchRebuildTaskStoreTest - M13 async index rebuild. Asserts the task state
#     really lands in Redis through the Spring-managed redis template (key names,
#     TTL, SUBMITTED -> RUNNING -> SUCCEEDED). A mock would only prove that the
#     mock works, not that the wiring works.
#   Ac10JwtTokenTest - M15 JWT login. Signing/verification depends on Spring
#     Security property binding (secret, TTL) plus the real filter chain, and the
#     ticket is AC-10, so the naming rule forces it into Ac*Test.java.
#   BulkUpdateStalenessTest / TagBindingConsistencyTest /
#   ViewCountConcurrencyTest / PasswordHashCompatTest - pre-M6 integration tests
#     (batch-update staleness, tag binding consistency, view-count concurrency
#     against a real transaction, bcrypt hash compatibility). Never Mockito tests.
#
# The check runs in BOTH directions so the list cannot rot either way:
#   (a) a @SpringBootTest class that is NOT listed   -> offender
#   (b) a listed class that has NO @SpringBootTest   -> stale entry
$springWhitelist = @(
    'SearchRebuildTaskStoreTest',
    'Ac10JwtTokenTest',
    'BulkUpdateStalenessTest',
    'TagBindingConsistencyTest',
    'ViewCountConcurrencyTest',
    'PasswordHashCompatTest'
)
$springOffenders = @()
$springSeen = @()
foreach ($f in $TestFiles) {
    if ((Read-Text $f) -notmatch '@SpringBootTest') { continue }
    $base = (Split-Path $f -Leaf) -replace '\.java$', ''
    $springSeen += $base
    if ($springWhitelist -notcontains $base) { $springOffenders += ($base + ' (not whitelisted)') }
}
foreach ($w in $springWhitelist) {
    if ($springSeen -notcontains $w) { $springOffenders += ($w + ' (whitelisted but no @SpringBootTest)') }
}
Check 'A4.spring-context-only-where-whitelisted' $springOffenders.Count 0
if ($springOffenders.Count -gt 0) { Write-Host ('      offenders: ' + ($springOffenders -join ', ')) }
else { Write-Host ('      whitelisted integration tests = ' + $springSeen.Count + ' : ' + ($springSeen -join ', ')) }
$mockito = Count-In $acTestFiles '@ExtendWith\(MockitoExtension\.class\)'
Check-True 'A5.mockito-extension-used' ($mockito -ge 8) ('annotations=' + $mockito)

# surefire summary of the last full run.
# NOTE (counter-drift bug, fixed 2026-10-15): these locals used to be named
# $run / $fail / $err. PowerShell variable names are CASE-INSENSITIVE, so the
# bare $fail in this block WAS the same variable as the script-scope failure
# counter $script:Fail -- assigning 0 to it here silently wiped every failure
# already recorded by the A1..A5 checks above (that is why the script could
# print a [FAIL] line and still summarise "PASS=49 FAIL=0" with exit code 0).
# Keep every local here prefixed so it can never alias $Pass / $Fail again.
$surefire = Join-Path $Repo 'backend\target\surefire-reports'
$sfRun = 0; $sfFail = 0; $sfErr = 0
if (Test-Path $surefire) {
    foreach ($f in @(Get-ChildItem $surefire -Filter '*.txt')) {
        $t = Read-Text $f.FullName
        if ($t -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+)') {
            $sfRun += [int]$Matches[1]; $sfFail += [int]$Matches[2]; $sfErr += [int]$Matches[3]
        }
    }
}
Check-True 'A6.surefire-ran' ($sfRun -gt 0) ('tests=' + $sfRun)
Check 'A7.surefire.failures' $sfFail 0
Check 'A8.surefire.errors' $sfErr 0
Check-True 'A9.surefire.total-at-least-30' ($sfRun -ge 30) ('tests=' + $sfRun)

# ---------------------------------------------------------------- B. JPA red lines
Write-Host ''
Write-Host 'B. T6.6 entity/query red lines (EAGER 0, layering, @Valid, paired @Modifying)'
Check 'B1.eager-count' (Count-In $MainFiles 'FetchType\.EAGER') 0
$ctrlFiles = @($MainFiles | Where-Object { $_.ToLower().Contains('\controller\') })
$ctrlRepoImports = 0
foreach ($f in $ctrlFiles) {
    $text = Read-Text $f
    $ctrlRepoImports += ([regex]::Matches($text, 'import com\.campusswap\.\w+\.repository\.')).Count
}
Check 'B2.controller-imports-repository' $ctrlRepoImports 0
$reqBody = 0; $reqBodyNoValid = 0
foreach ($f in $MainFiles) {
    foreach ($line in (Read-Text $f) -split "`r?`n") {
        if ($line -match '@RequestBody' -and $line -notmatch '^\s*(\*|//|/\*)') {
            $reqBody++
            if ($line -notmatch '@Valid') { $reqBodyNoValid++ }
        }
    }
}
Check-True 'B3.request-body-count' ($reqBody -gt 0) ('lines=' + $reqBody)
Check 'B4.request-body-without-valid' $reqBodyNoValid 0
$modifying = 0; $modifyingUnpaired = 0
foreach ($f in $MainFiles) {
    foreach ($line in (Read-Text $f) -split "`r?`n") {
        if ($line -match '@Modifying' -and $line -notmatch '^\s*(\*|//|/\*)') {
            $modifying++
            if ($line -notmatch 'clearAutomatically = true, flushAutomatically = true') { $modifyingUnpaired++ }
        }
    }
}
Check-True 'B5.modifying-count' ($modifying -ge 19) ('annotations=' + $modifying)
Check 'B6.modifying-unpaired' $modifyingUnpaired 0
Check 'B7.readonly-tags-add-remove' (Count-In $MainFiles 'getTags\(\)\.(add|remove)\(') 0
Check 'B8.money-float-backend' (Count-In $MainFiles '\b(double|float|Double|Float|BigDecimal)\b') 0
Check 'B9.money-float-frontend' (Count-In $FeFiles '\b(float|double|BigDecimal)\b') 0
Check 'B10.frontend-any' (Count-In $FeFiles ':\s*any\b|<any>|as any') 0
Check 'B11.frontend-inline-style' (Count-In $FeFiles 'style="') 0

# ---------------------------------------------------------------- C. DDL
Write-Host ''
Write-Host 'C. MASTER-PLAN 2.2 DDL rules re-checked in schema.sql'
Check 'C1.create-table' (Count-In $SqlFiles 'CREATE TABLE') 15
Check 'C2.engine-innoDB' (Count-In $SqlFiles 'ENGINE=InnoDB') 15
Check 'C3.collate-utf8mb4' (Count-In $SqlFiles 'utf8mb4_unicode_ci') 17
Check 'C4.no-foreign-key' (Count-In $SqlFiles 'FOREIGN KEY|REFERENCES') 0
Check 'C5.table-comments' (Count-In $SqlFiles "COMMENT='") 15

# ---------------------------------------------------------------- D. documents
Write-Host ''
Write-Host 'D. T6.3/T6.4/T6.5/T6.6/T6.7 evidence documents and machine anchors'
$codeReview = Join-Path $Qa 'CODE_REVIEW.md'
$checklist = Join-Path $Qa 'TEST_CHECKLIST.md'
$explain = Join-Path $Qa 'EXPLAIN-NOTES.md'
$closure = Join-Path $Qa 'M6-CLOSURE.md'
$probe = Join-Path $Qa 'probe-sql-counts.mjs'
$httpCheck = Join-Path $Qa 'verify-m6-http.ps1'
$reload = Join-Path $Qa 'reload-db.ps1'
Check 'D1.file.CODE_REVIEW.md' (Test-Path $codeReview) $true
Check 'D2.file.M6-CLOSURE.md' (Test-Path $closure) $true
Check 'D3.file.probe-sql-counts.mjs' (Test-Path $probe) $true
Check 'D4.file.verify-m6-http.ps1' (Test-Path $httpCheck) $true
Check 'D5.file.reload-db.ps1' (Test-Path $reload) $true
$cr = Read-Text $codeReview
$crMissing = @()
foreach ($id in @('R1','R2','R3','R4','R5','R6','DDL1','DDL2','DDL3','DDL4','DDL5','DDL6','L1','L2','L3','L4','L5','F1','F2','F3','F4')) {
    if ($cr -notmatch ('\b' + $id + '\b')) { $crMissing += $id }
}
Check 'D6.code-review-sections' $crMissing.Count 0
if ($crMissing.Count -gt 0) { Write-Host ('      missing: ' + ($crMissing -join ', ')) }
Check-True 'D7.code-review-anchor' ($cr.Contains('M6-CODE-REVIEW')) ''
$tc = Read-Text $checklist
Check-True 'D8.checklist-sql-anchor' ($tc.Contains('M6-SQL-COUNTS')) ''
Check-True 'D9.checklist-exception-anchor' ($tc.Contains('M6-EXCEPTION-PATHS')) ''
$tcMissing = @()
foreach ($ep in @('/api/documents/manage', '/api/documents/mine', '/api/review/documents', '/api/favorites', '/api/users', '/api/roles', '/api/permissions/tree', '/api/documents/trash', '/api/tags', '/api/depts/tree', '/api/categories/tree', '/api/roles/{id}/permissions', '/api/depts/{id}/roles', '/api/stats/overview')) {
    if (-not $tc.Contains($ep)) { $tcMissing += $ep }
}
Check 'D10.checklist-covers-budget-table' $tcMissing.Count 0
if ($tcMissing.Count -gt 0) { Write-Host ('      missing: ' + ($tcMissing -join ', ')) }
$ex = Read-Text $explain
Check-True 'D11.explain-m6-anchor' ($ex.Contains('M6-INDEX-REGRESSION')) ''
Check-True 'D12.explain-index-cat-status' ($ex.Contains('idx_doc_cat_status_updated')) ''
Check-True 'D13.explain-index-status' ($ex.Contains('idx_doc_status_updated')) ''
Check-True 'D14.explain-perf-volume' ($ex.Contains('20045') -or $ex.Contains('20 045') -or $ex.Contains('20,045')) ''

# ---------------------------------------------------------------- E. plan/kanban
Write-Host ''
Write-Host 'E. T6.5 master plan checkboxes and change notes'
$plan = Read-Text (Join-Path $Repo 'docs\MASTER-PLAN.md')
Check 'E1.m6-open-checkboxes' (([regex]::Matches($plan, '(?m)^- \[ \] \*\*T6\.')).Count) 0
$t6 = 0
foreach ($n in 1..7) { if ($plan -match ('(?m)^- \[x\] \*\*T6\.' + $n + '\*\*')) { $t6++ } }
Check 'E2.m6-checked-items' $t6 7
$m5Line = @(($plan -split "`r?`n") | Where-Object { $_ -like '### M5 *' })
$m6Line = @(($plan -split "`r?`n") | Where-Object { $_ -like '### M6 *' })
Check-True 'E3.m5-header-has-commit' ($m5Line.Count -ge 1 -and $m5Line[0] -match '`[0-9a-f]{7}`') ($m5Line -join '')
Check-True 'E4.m6-header-has-commit' ($m6Line.Count -ge 1 -and $m6Line[0] -match '`[0-9a-f]{7}`') ($m6Line -join '')

# ---------------------------------------------------------------- F. ASCII purity
Write-Host ''
Write-Host 'F. new checker scripts stay pure ASCII'
foreach ($pair in @(@('F1.verify-m6.ps1', (Join-Path $Qa 'verify-m6.ps1')), @('F2.verify-m6-http.ps1', $httpCheck), @('F3.reload-db.ps1', $reload))) {
    $bytes = @()
    if (Test-Path $pair[1]) { $bytes = [System.IO.File]::ReadAllBytes($pair[1]) }
    $nonAscii = @($bytes | Where-Object { $_ -gt 127 }).Count
    Check $pair[0] $nonAscii 0
}

# ---------------------------------------------------------------- G. SQL evidence
Write-Host ''
Write-Host 'G. T6.7 measured SQL counts (machine-readable evidence)'
foreach ($pair in @(@('G1.seed-json', $SeedJson), @('G2.perf-json', $PerfJson))) {
    if (-not (Test-Path $pair[1])) { Check-True $pair[0] $false ('missing ' + $pair[1]); continue }
    $doc = Read-Text $pair[1] | ConvertFrom-Json
    $rows = @($doc.rows)
    $bad = @($rows | Where-Object { -not $_.ok })
    Check-True ($pair[0] + '.endpoints') ($rows.Count -ge 18) ('endpoints=' + $rows.Count)
    Check ($pair[0] + '.over-budget') $bad.Count 0
    if ($bad.Count -gt 0) { $bad | ForEach-Object { Write-Host ('      over budget: ' + $_.label) } }
}

Write-Host ''
Write-Host '============================================================='
# TOTAL must always equal PASS + FAIL, and PASS + FAIL must equal the number of
# Check / Check-True call sites above (50). If TOTAL ever drifts from that, a
# counter was aliased again -- see the $sfRun/$sfFail/$sfErr note in section A.
Write-Host (' RESULT: PASS=' + $script:Pass + ' FAIL=' + $script:Fail + ' TOTAL=' + ($script:Pass + $script:Fail))
if ($script:Fail -gt 0) { Write-Host (' failed: ' + ($script:Failures -join ', ')) }
Write-Host '============================================================='
exit $script:Fail
