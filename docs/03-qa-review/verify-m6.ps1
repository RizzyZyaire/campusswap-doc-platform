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
# only the NEW BDD test classes must be context-free; the four pre-existing
# integration tests (BulkUpdateStaleness / TagBindingConsistency / ViewCount /
# PasswordHashCompat) are @SpringBootTest by design and stay untouched.
$springBootTests = Count-In $acTestFiles '@SpringBootTest'
$mockito = Count-In $acTestFiles '@ExtendWith\(MockitoExtension\.class\)'
Check 'A4.no-spring-context-in-new-tests' $springBootTests 0
Check-True 'A5.mockito-extension-used' ($mockito -ge 8) ('annotations=' + $mockito)

# surefire summary of the last full run
$surefire = Join-Path $Repo 'backend\target\surefire-reports'
$run = 0; $fail = 0; $err = 0
if (Test-Path $surefire) {
    foreach ($f in @(Get-ChildItem $surefire -Filter '*.txt')) {
        $t = Read-Text $f.FullName
        if ($t -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+)') {
            $run += [int]$Matches[1]; $fail += [int]$Matches[2]; $err += [int]$Matches[3]
        }
    }
}
Check-True 'A6.surefire-ran' ($run -gt 0) ('tests=' + $run)
Check 'A7.surefire.failures' $fail 0
Check 'A8.surefire.errors' $err 0
Check-True 'A9.surefire.total-at-least-30' ($run -ge 30) ('tests=' + $run)

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
Check 'C1.create-table' (Count-In $SqlFiles 'CREATE TABLE') 14
Check 'C2.engine-innoDB' (Count-In $SqlFiles 'ENGINE=InnoDB') 14
Check 'C3.collate-utf8mb4' (Count-In $SqlFiles 'utf8mb4_unicode_ci') 16
Check 'C4.no-foreign-key' (Count-In $SqlFiles 'FOREIGN KEY|REFERENCES') 0
Check 'C5.table-comments' (Count-In $SqlFiles "COMMENT='") 14

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
Write-Host (' RESULT: PASS=' + $script:Pass + ' FAIL=' + $script:Fail)
if ($script:Fail -gt 0) { Write-Host (' failed: ' + ($script:Failures -join ', ')) }
Write-Host '============================================================='
exit $script:Fail
