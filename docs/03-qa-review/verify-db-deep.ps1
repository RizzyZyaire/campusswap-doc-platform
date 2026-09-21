# verify-db-deep.ps1 -- deep database verification (pure ASCII, re-runnable)
# Usage: $env:DB_PASSWORD='...'; powershell -NoProfile -ExecutionPolicy Bypass -File verify-db-deep.ps1
# Angles NOT covered by verify-m2.ps1:
#   D1 schema.sql (DDL file) vs live database, column by column
#   D2 logical foreign keys: orphan-row detection (we deliberately have no DB FKs)
#   D3 tree integrity: ancestors must equal the parent chain (sys_permission/sys_dept/doc_category)
#   D4 seed semantics: role-permission sets must equal PRD 3.3 exactly (not just counts)
#   D5 audit-column behaviour: DEFAULT CURRENT_TIMESTAMP + ON UPDATE (tested then rolled back)
#   D6 docs vs DB: PRD table list and ARCHITECTURE index names must match reality

param(
  [string]$Mysql    = 'D:\DevEnv\03_MySQL\bin\mysql.exe',
  [string]$User     = 'root',
  [string]$Password = $env:DB_PASSWORD,
  [string]$Database = 'campusswap_db',
  [string]$Repo     = 'D:\DevEnv\projects\campusswap'
)

$ErrorActionPreference = 'Stop'
$script:fails = 0; $script:pass = 0
function Check { param([string]$Nm,[bool]$Ok,[string]$Detail)
  if ($Ok) { $script:pass++; Write-Host ("[PASS] {0} -- {1}" -f $Nm,$Detail) }
  else     { $script:fails++; Write-Host ("[FAIL] {0} -- {1}" -f $Nm,$Detail) -ForegroundColor Red } }

if (-not (Test-Path -LiteralPath $Mysql)) { Write-Host "MISSING mysql client"; exit 1 }
if ([string]::IsNullOrWhiteSpace($Password)) { Write-Host "MISSING `$env:DB_PASSWORD"; exit 1 }
$cnf = Join-Path $env:TEMP ("campusswap-deep-{0}.cnf" -f $PID)
Set-Content -LiteralPath $cnf -Encoding ASCII -Value @('[client]', "user=$User", "password=$Password", "database=$Database")

function Scalar { param([string]$Sql)
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  $r = @(& $Mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -B $Database -e $Sql 2>&1 |
        Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
  $ErrorActionPreference = $prev
  if ($r.Count -eq 0) { return '' } else { return ([string]$r[0]).Trim() } }
function Rows { param([string]$Sql)
  $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  $r = @(& $Mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -B $Database -e $Sql 2>&1 |
        Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
  $ErrorActionPreference = $prev
  return @($r | Where-Object { $_ -ne '' }) }

# ---------- D1: DDL file vs live database, column by column -------------------
$ddl = [IO.File]::ReadAllText((Join-Path $Repo 'backend\sql\schema.sql'), [Text.Encoding]::UTF8)
$blocks = [regex]::Matches($ddl, '(?s)CREATE TABLE `(?<t>[a-z_]+)` \((?<b>.*?)\n\) ENGINE')
$fileCols = @{}
foreach ($m in $blocks) {
  $t = $m.Groups['t'].Value
  $cols = [regex]::Matches($m.Groups['b'].Value, '(?m)^\s+`(?<c>[a-z_]+)`\s+') | ForEach-Object { $_.Groups['c'].Value }
  $fileCols[$t] = $cols
}
$dbCols = @{}
foreach ($line in (Rows "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$Database' ORDER BY TABLE_NAME, ORDINAL_POSITION;")) {
  $p = $line -split "`t"
  if ($p.Count -ge 2) { if (-not $dbCols.ContainsKey($p[0])) { $dbCols[$p[0]] = @() }; $dbCols[$p[0]] += $p[1] }
}
$diff = @()
foreach ($t in $fileCols.Keys) {
  if (-not $dbCols.ContainsKey($t)) { $diff += "table-missing-in-db:$t"; continue }
  foreach ($c in $fileCols[$t]) { if ($dbCols[$t] -notcontains $c) { $diff += "$t.$c(file-only)" } }
  foreach ($c in $dbCols[$t])   { if ($fileCols[$t] -notcontains $c) { $diff += "$t.$c(db-only)" } }
}
foreach ($t in $dbCols.Keys) { if (-not $fileCols.ContainsKey($t)) { $diff += "table-missing-in-file:$t" } }
Check 'D1 ddl-vs-db-columns' ($diff.Count -eq 0) ("tables-in-file=" + $fileCols.Count + " tables-in-db=" + $dbCols.Count + " diff=" + $(if ($diff.Count) { $diff -join ',' } else { '0' }))

# ---------- D2: logical FK integrity (orphan rows) ---------------------------
$fkChecks = @(
  @{ n='user.dept';        sql="SELECT COUNT(*) FROM sys_user c LEFT JOIN sys_dept p ON p.id=c.dept_id WHERE p.id IS NULL" },
  @{ n='dept.parent';      sql="SELECT COUNT(*) FROM sys_dept c LEFT JOIN sys_dept p ON p.id=c.parent_id WHERE c.parent_id<>0 AND p.id IS NULL" },
  @{ n='perm.parent';      sql="SELECT COUNT(*) FROM sys_permission c LEFT JOIN sys_permission p ON p.id=c.parent_id WHERE c.parent_id<>0 AND p.id IS NULL" },
  @{ n='category.parent';  sql="SELECT COUNT(*) FROM doc_category c LEFT JOIN doc_category p ON p.id=c.parent_id WHERE c.parent_id<>0 AND p.id IS NULL" },
  @{ n='user_role';        sql="SELECT COUNT(*) FROM sys_user_role r LEFT JOIN sys_user u ON u.id=r.user_id LEFT JOIN sys_role o ON o.id=r.role_id WHERE u.id IS NULL OR o.id IS NULL" },
  @{ n='user_permission';  sql="SELECT COUNT(*) FROM sys_user_permission r LEFT JOIN sys_user u ON u.id=r.user_id LEFT JOIN sys_permission p ON p.id=r.permission_id WHERE u.id IS NULL OR p.id IS NULL" },
  @{ n='role_permission';  sql="SELECT COUNT(*) FROM sys_role_permission r LEFT JOIN sys_role o ON o.id=r.role_id LEFT JOIN sys_permission p ON p.id=r.permission_id WHERE o.id IS NULL OR p.id IS NULL" },
  @{ n='dept_role';        sql="SELECT COUNT(*) FROM sys_dept_role r LEFT JOIN sys_dept d ON d.id=r.dept_id LEFT JOIN sys_role o ON o.id=r.role_id WHERE d.id IS NULL OR o.id IS NULL" },
  @{ n='doc.category';     sql="SELECT COUNT(*) FROM doc_document c LEFT JOIN doc_category p ON p.id=c.category_id WHERE c.category_id<>0 AND p.id IS NULL" },
  @{ n='doc.author';       sql="SELECT COUNT(*) FROM doc_document c LEFT JOIN sys_user u ON u.id=c.created_by WHERE u.id IS NULL" },
  @{ n='doc.derived_from'; sql="SELECT COUNT(*) FROM doc_document c LEFT JOIN doc_document p ON p.id=c.derived_from_id WHERE c.derived_from_id IS NOT NULL AND p.id IS NULL" },
  @{ n='version.document'; sql="SELECT COUNT(*) FROM doc_version r LEFT JOIN doc_document d ON d.id=r.document_id WHERE d.id IS NULL" },
  @{ n='tag_rel';          sql="SELECT COUNT(*) FROM doc_document_tag_rel r LEFT JOIN doc_document d ON d.id=r.document_id LEFT JOIN doc_tag t ON t.id=r.tag_id WHERE d.id IS NULL OR t.id IS NULL" },
  @{ n='favorite';         sql="SELECT COUNT(*) FROM doc_favorite f LEFT JOIN sys_user u ON u.id=f.user_id LEFT JOIN doc_document d ON d.id=f.document_id WHERE u.id IS NULL OR d.id IS NULL" }
)
$fkBad = @()
foreach ($c in $fkChecks) { $v = Scalar $c.sql; if ($v -ne '0') { $fkBad += "$($c.n)=$v" } }
Check 'D2 no-orphan-rows' ($fkBad.Count -eq 0) ("checked=" + $fkChecks.Count + " orphans=" + $(if ($fkBad.Count) { $fkBad -join ',' } else { '0' }))

# ---------- D3: tree integrity: ancestors == parent chain --------------------
$treeSql = @{
  sys_permission = "SELECT COUNT(*) FROM sys_permission c LEFT JOIN sys_permission p ON p.id=c.parent_id WHERE c.ancestors <> (CASE WHEN c.parent_id=0 THEN '0' ELSE CONCAT(p.ancestors,',',p.id) END)"
  sys_dept       = "SELECT COUNT(*) FROM sys_dept c LEFT JOIN sys_dept p ON p.id=c.parent_id WHERE c.ancestors <> (CASE WHEN c.parent_id=0 THEN '0' ELSE CONCAT(p.ancestors,',',p.id) END)"
  doc_category   = "SELECT COUNT(*) FROM doc_category c LEFT JOIN doc_category p ON p.id=c.parent_id WHERE c.ancestors <> (CASE WHEN c.parent_id=0 THEN '0' ELSE CONCAT(p.ancestors,',',p.id) END)"
}
$treeBad = @()
foreach ($t in $treeSql.Keys) { $v = Scalar $treeSql[$t]; if ($v -ne '0') { $treeBad += "$t=$v" } }
Check 'D3 ancestors-chain-consistent' ($treeBad.Count -eq 0) ("trees=3 mismatches=" + $(if ($treeBad.Count) { $treeBad -join ',' } else { '0' }))

# ---------- D4: role-permission sets equal PRD 3.3 exactly -------------------
# NOTE: compare SORTED SETS, never concatenated strings (column order is alphabetical in SQL)
function CmpSet { param($a, $b) return (((@($a) | Sort-Object) -join ',') -eq ((@($b) | Sort-Object) -join ',')) }
$staff = @(Rows "SELECT p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.role_id=1") | ForEach-Object { $_.Trim() }
$docad = @(Rows "SELECT p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.role_id=2") | ForEach-Object { $_.Trim() }
$sysad = @(Rows "SELECT p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.role_id=3") | ForEach-Object { $_.Trim() }
$wantStaff = @('doc:center','doc:create','doc:delete','doc:derive','doc:edit','doc:favorite','doc:mine','doc:publish','doc:restore','doc:search','doc:upload')
$wantDocad = @($wantStaff + @('doc:archive','doc:audit','doc:category','doc:category:edit','doc:manage','doc:offline','doc:reject','doc:review','doc:tag:edit'))
$wantSysad = @(Rows "SELECT code FROM sys_permission") | ForEach-Object { $_.Trim() }
Check 'D4a staff-set'    (CmpSet $staff $wantStaff) ("got " + $staff.Count + " codes, expected " + $wantStaff.Count + " (PRD 3.3)")
Check 'D4b docadmin-set' (CmpSet $docad $wantDocad) ("got " + $docad.Count + " codes, expected " + $wantDocad.Count + " (PRD 3.3)")
Check 'D4c sysadmin-set' (CmpSet $sysad $wantSysad) ("got " + $sysad.Count + " codes, expected all " + $wantSysad.Count + " permissions")

# ---------- D5: audit-column behaviour (insert/update then rollback) --------
$prevEap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$sqlTxn = @"
START TRANSACTION;
INSERT INTO doc_document (category_id,title,summary,status,version_num,price_cents,created_by) VALUES (1,'DEEP-AUDIT-PROBE','probe','DRAFT',1,0,1);
SET @c = (SELECT created_at FROM doc_document WHERE title='DEEP-AUDIT-PROBE');
SET @u1 = (SELECT updated_at FROM doc_document WHERE title='DEEP-AUDIT-PROBE');
SET @by = (SELECT created_by FROM doc_document WHERE title='DEEP-AUDIT-PROBE');
SELECT CONCAT('created_at_set=', IF(@c IS NULL,'NO','YES'), ' created_by=', @by) AS r;
DO SLEEP(1.2);
UPDATE doc_document SET summary='probe-updated' WHERE title='DEEP-AUDIT-PROBE';
SELECT CONCAT('updated_at_advanced=', IF((SELECT updated_at FROM doc_document WHERE title='DEEP-AUDIT-PROBE') > @u1,'YES','NO')) AS r;
ROLLBACK;
SELECT CONCAT('rolled_back=', IF((SELECT COUNT(*) FROM doc_document WHERE title='DEEP-AUDIT-PROBE')=0,'YES','NO')) AS r;
"@
$out = @(& $Mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -B $Database -e $sqlTxn 2>&1 |
        Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
$ErrorActionPreference = $prevEap
$joined = ($out -join ' | ')
$auditOk = ($joined -match 'created_at_set=YES') -and ($joined -match 'created_by=1') -and ($joined -match 'updated_at_advanced=YES') -and ($joined -match 'rolled_back=YES')
Check 'D5 audit-columns-behaviour' $auditOk $joined

# ---------- D6: docs vs reality ---------------------------------------------
# NOTE: keep this ASCII-only -- a CJK literal in a regex is itself a bug under PS 5.1 (GBK misread).
# Extract every 'sys_xxx' / 'doc_xxx' token from the PRD (quote lines = changelog excluded) and
# require each to be a real table in the database.
$prd = [IO.File]::ReadAllText((Join-Path $Repo 'docs\01-requirements\PRD.md'), [Text.Encoding]::UTF8)
$prdBody = (($prd -split "`n" | Where-Object { $_ -notmatch '^\s*>' }) -join "`n")
$prdTables = [regex]::Matches($prdBody, '(?<![\w.])(?:sys|doc)_[a-z_]{2,}(?![\w.])') |
             ForEach-Object { $_.Value } | Sort-Object -Unique
$dbTables = (Rows "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' ORDER BY TABLE_NAME") | ForEach-Object { $_.Trim() }
$prdMissing = @($prdTables | Where-Object { $dbTables -notcontains $_ })
Check 'D6a prd-table-names-exist' ($prdMissing.Count -eq 0) ("PRD tokens=" + $prdTables.Count + " not-in-db=" + $(if ($prdMissing.Count) { $prdMissing -join ',' } else { '0' }))

$arc = [IO.File]::ReadAllText((Join-Path $Repo 'docs\02-design\ARCHITECTURE.md'), [Text.Encoding]::UTF8)
$arcBad = @()
foreach ($n in @('idx_doc_cat_status_updated','idx_doc_status_updated','idx_doc_created_by_updated','idx_fav_doc')) {
  if ($arc -notmatch $n) { $arcBad += "not-mentioned:$n" }
}
if ($arc -match 'idx_doc_created_by[^_]') { $arcBad += 'stale-name:idx_doc_created_by' }
$dbIdx = (Rows "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$Database'") | ForEach-Object { $_.Trim() }
foreach ($n in @('idx_doc_cat_status_updated','idx_doc_status_updated','idx_doc_created_by_updated')) {
  if ($dbIdx -notcontains $n) { $arcBad += "missing-in-db:$n" }
}
Check 'D6b architecture-index-names' ($arcBad.Count -eq 0) $(if ($arcBad.Count) { $arcBad -join ',' } else { 'ARCH & DB agree on the 3 document indexes' })

# ---------- summary ----------------------------------------------------------
if (Test-Path -LiteralPath $cnf) { Remove-Item -LiteralPath $cnf -Force }
Write-Host ''
Write-Host ("RESULT: pass={0} fail={1}" -f $script:pass, $script:fails)
if ($script:fails -gt 0) { exit 1 } else { Write-Host 'DEEP DB CHECKS: ALL GREEN'; exit 0 }
