# verify-m2.ps1 -- M2 database self-check (pure ASCII output only!)
# Usage:
#   $env:MYSQL_ROOT_PASSWORD='123456'; powershell -NoProfile -ExecutionPolicy Bypass -File verify-m2.ps1
#   (legacy $env:DB_PASSWORD still works; prefer MYSQL_ROOT_PASSWORD -- DB_PASSWORD is also
#    interpolated by Spring as the campusswap_dev password in application-dev/prod.yml)
# Re-runnable. Requires the campusswap_db schema to be loaded.
# NOTE: keep this file pure ASCII -- PS 5.1 reads BOM-less UTF-8 as ANSI and CJK literals break the parser.

param(
  [string]$Mysql    = 'D:\DevEnv\03_MySQL\bin\mysql.exe',
  [string]$User     = 'root',
  [string]$Password = $(if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { $env:DB_PASSWORD }),
  [string]$Database = 'campusswap_db'
)

$ErrorActionPreference = 'Stop'
$script:fails = 0
$script:pass  = 0

function Check {
  param([string]$Nm, [bool]$Ok, [string]$Detail)
  if ($Ok) { $script:pass++; Write-Host ("[PASS] {0} -- {1}" -f $Nm, $Detail) }
  else     { $script:fails++; Write-Host ("[FAIL] {0} -- {1}" -f $Nm, $Detail) -ForegroundColor Red }
}

if (-not (Test-Path -LiteralPath $Mysql)) { Write-Host "MISSING mysql client: $Mysql"; exit 1 }
if ([string]::IsNullOrWhiteSpace($Password)) { Write-Host "MISSING password: set `$env:DB_PASSWORD"; exit 1 }

# credentials go through a throw-away defaults file: keeps the password out of the command line
# (and avoids mysql's stderr warning, which PowerShell would turn into a terminating error)
$cnf = Join-Path $env:TEMP ("campusswap-verify-{0}.cnf" -f $PID)
Set-Content -LiteralPath $cnf -Encoding ASCII -Value @('[client]', "user=$User", "password=$Password", "database=$Database")

function Query {
  param([string]$Sql)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  # $Database is passed explicitly: without a default schema every unqualified table name fails
  # with "No database selected" (which stderr redirection would silently swallow).
  $out = & $Mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -B $Database -e $Sql 2>&1 |
         Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }
  $ErrorActionPreference = $prev
  return @($out)
}
function Scalar {
  param([string]$Sql)
  # NOTE: wrap in @() again -- PowerShell unwraps single-element arrays on return,
  # so without this $r would be a String and $r[0] would be its first character.
  $r = @(Query $Sql)
  if ($r.Count -eq 0) { return '' }
  return ([string]$r[0]).Trim()
}

$db = $Database
$Q  = "'$db'"

# --- C1: database exists with the right charset / collation -------------------
$cs = Scalar "SELECT CONCAT(DEFAULT_CHARACTER_SET_NAME,'/',DEFAULT_COLLATION_NAME) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=$Q;"
Check 'C1 db-charset' ($cs -eq 'utf8mb4/utf8mb4_unicode_ci') "expected utf8mb4/utf8mb4_unicode_ci, got '$cs'"

# --- C2: 14 tables ------------------------------------------------------------
$tblCount = Scalar "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=$Q;"
Check 'C2 table-count-14' ($tblCount -eq '14') "expected 14, got $tblCount"

# --- C3: engine + collation for every table -----------------------------------
$badEngine = Scalar "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=$Q AND (ENGINE<>'InnoDB' OR TABLE_COLLATION<>'utf8mb4_unicode_ci');"
Check 'C3 engine-and-collation' ($badEngine -eq '0') "tables not InnoDB/utf8mb4_unicode_ci: $badEngine (expect 0)"

# --- C4: zero foreign keys ----------------------------------------------------
$fk = Scalar "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=$Q AND CONSTRAINT_TYPE='FOREIGN KEY';"
Check 'C4 zero-foreign-keys' ($fk -eq '0') "foreign keys: $fk (expect 0)"

# --- C5: primary keys -- 8 tables with AUTO_INCREMENT, 6 junction tables with composite PK
$noAuto = Scalar "SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) FROM (SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=$Q AND COLUMN_KEY='PRI' AND EXTRA NOT LIKE '%auto_increment%') t;"
$expectNoAuto = 'doc_document_tag_rel,doc_favorite,sys_dept_role,sys_role_permission,sys_user_permission,sys_user_role'
Check 'C5 composite-key-tables' ($noAuto -eq $expectNoAuto) "expected [$expectNoAuto], got [$noAuto]"

$autoCount = Scalar "SELECT COUNT(DISTINCT TABLE_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=$Q AND COLUMN_KEY='PRI' AND EXTRA LIKE '%auto_increment%';"
Check 'C6 auto-increment-tables' ($autoCount -eq '8') "expected 8 tables with AUTO_INCREMENT PK, got $autoCount"

# --- C7: every column and every table has a COMMENT ---------------------------
$noColCmt = Scalar "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=$Q AND (COLUMN_COMMENT IS NULL OR COLUMN_COMMENT='');"
Check 'C7 column-comments' ($noColCmt -eq '0') "columns without COMMENT: $noColCmt (expect 0)"

$noTblCmt = Scalar "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=$Q AND (TABLE_COMMENT IS NULL OR TABLE_COMMENT='');"
Check 'C8 table-comments' ($noTblCmt -eq '0') "tables without COMMENT: $noTblCmt (expect 0)"

# --- C9: price_cents is INT UNSIGNED -----------------------------------------
$pc = Scalar "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=$Q AND COLUMN_NAME='price_cents';"
Check 'C9 price-cents-unsigned' ($pc -eq 'int unsigned') "expected 'int unsigned', got '$pc'"

# --- C10: audit columns on the 8 business tables ------------------------------
$audit = Scalar "SELECT COUNT(*) FROM (SELECT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=$Q AND COLUMN_NAME IN ('created_at','created_by','updated_at','updated_by','deleted') GROUP BY TABLE_NAME HAVING COUNT(DISTINCT COLUMN_NAME)=5) t;"
Check 'C10 audit-columns' ($audit -eq '8') "tables carrying all 5 audit columns: $audit (expect 8)"

# --- C11: no legacy column names ---------------------------------------------
$legacy = Scalar "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=$Q AND COLUMN_NAME IN ('is_deleted','is_enabled','create_at','create_by','update_at','update_by','author_id','operator_id','perm_code','perm_name','perm_type','role_code','role_name','dept_name','tag_name','category_name','sort_num','order_num');"
Check 'C11 no-legacy-columns' ($legacy -eq '0') "legacy columns found: $legacy (expect 0)"

# --- C12: expected indexes present -------------------------------------------
$indexes = (Query "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q;") | ForEach-Object { $_.Trim() }
$needIdx = @(
  'idx_sys_dept_parent','uk_sys_user_username','idx_sys_user_dept','uk_sys_role_code',
  'uk_sys_permission_code','idx_sys_perm_parent','idx_user_role_role','idx_user_perm_perm',
  'idx_role_perm_perm','idx_dept_role_role',
  'idx_doc_cat_status_updated','idx_doc_status_updated','idx_doc_created_by_updated',
  'idx_doc_status_publish','idx_doc_status_view','idx_doc_cat_status_publish','idx_doc_cat_status_view',
  'idx_doc_deleted_updated','idx_doc_deleted_publish','idx_doc_deleted_view',
  'uk_doc_version','idx_doc_category_parent','uk_doc_tag_name','idx_rel_tag_doc','idx_fav_doc',
  'ft_doc_search'
)
$missIdx = @(); foreach ($i in $needIdx) { if ($indexes -notcontains $i) { $missIdx += $i } }
Check 'C12 expected-indexes' ($missIdx.Count -eq 0) ("indexes=" + $indexes.Count + " missing=" + $(if ($missIdx.Count) { $missIdx -join ',' } else { '0' }))

# --- C13: leftmost-prefix pair really exists (both index column orders) -------
$catIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_cat_status_updated';"))[0]
$staIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_status_updated';"))[0]
Check 'C13 leftmost-prefix-pair' (($catIdx -eq 'category_id,status,updated_at,deleted') -and ($staIdx -eq 'status,updated_at,deleted')) "cat=[$catIdx] status=[$staIdx]"

# --- C13b: full-text index exists with the ngram parser and the exact 3 columns
# MATCH() only accepts the FULL column list of a FULLTEXT index: a subset MATCH
# (e.g. MATCH(title,summary)) fails with ERROR 1191, so the column list is pinned here.
$ftIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='ft_doc_search';"))[0]
$ftSql = (@(Query "SHOW CREATE TABLE doc_document;")) -join ''
$ftNgram = ($ftSql -like '*ngram*')
Check 'C13b fulltext-ngram-index' (($ftIdx -eq 'title,summary,content_md') -and $ftNgram) "ft_doc_search=[$ftIdx] ngram_parser=$ftNgram"

# --- C13c: sort-option indexes keep the sort key right after the equality cols --
# DocumentSort exposes updatedAt_desc / publishAt_desc / viewCount_desc to the UI, so
# every one of them needs an index whose column order lets MySQL walk it in reverse
# instead of filing sorting the whole result set (M7 fix, see EXPLAIN-NOTES.md section 6).
$pubIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_status_publish';"))[0]
$viewIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_status_view';"))[0]
$pubCatIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_cat_status_publish';"))[0]
$viewCatIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_cat_status_view';"))[0]
$delUpdIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_deleted_updated';"))[0]
$delPubIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_deleted_publish';"))[0]
$delViewIdx = (@(Query "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=$Q AND INDEX_NAME='idx_doc_deleted_view';"))[0]
$sortOk = ($pubIdx -eq 'status,publish_at,deleted') -and ($viewIdx -eq 'status,view_count,deleted') -and
          ($pubCatIdx -eq 'category_id,status,publish_at,deleted') -and ($viewCatIdx -eq 'category_id,status,view_count,deleted') -and
          ($delUpdIdx -eq 'deleted,updated_at') -and ($delPubIdx -eq 'deleted,publish_at') -and ($delViewIdx -eq 'deleted,view_count')
Check 'C13c sort-option-indexes' $sortOk "publish=[$pubIdx] view=[$viewIdx] catPublish=[$pubCatIdx] catView=[$viewCatIdx] allStatus=[$delUpdIdx|$delPubIdx|$delViewIdx]"

# --- C14: seed data counts ---------------------------------------------------
$seeds = @(
  @{ n = 'permissions'; sql = 'SELECT COUNT(*) FROM sys_permission';      want = '39' },
  @{ n = 'roles';       sql = 'SELECT COUNT(*) FROM sys_role';            want = '3'  },
  @{ n = 'staff-perms'; sql = 'SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1'; want = '11' },
  @{ n = 'docadmin-perms'; sql = 'SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2'; want = '20' },
  @{ n = 'sysadmin-perms'; sql = 'SELECT COUNT(*) FROM sys_role_permission WHERE role_id=3'; want = '39' },
  @{ n = 'depts';       sql = 'SELECT COUNT(*) FROM sys_dept';            want = '3'  },
  @{ n = 'users';       sql = 'SELECT COUNT(*) FROM sys_user';            want = '3'  },
  @{ n = 'categories';  sql = 'SELECT COUNT(*) FROM doc_category';        want = '4'  },
  @{ n = 'tags';        sql = 'SELECT COUNT(*) FROM doc_tag';             want = '20' },
  @{ n = 'documents';   sql = 'SELECT COUNT(*) FROM doc_document';        want = '45' },
  @{ n = 'published';   sql = "SELECT COUNT(*) FROM doc_document WHERE status='PUBLISHED'"; want = '28' },
  @{ n = 'drafts';      sql = "SELECT COUNT(*) FROM doc_document WHERE status='DRAFT'";     want = '9'  },
  @{ n = 'archived';    sql = "SELECT COUNT(*) FROM doc_document WHERE status='ARCHIVED'";  want = '4'  },
  @{ n = 'trash';       sql = "SELECT COUNT(*) FROM doc_document WHERE status='TRASH'";     want = '4'  },
  @{ n = 'versions';    sql = 'SELECT COUNT(*) FROM doc_version';         want = '99' },
  @{ n = 'tag-rels';    sql = 'SELECT COUNT(*) FROM doc_document_tag_rel'; want = '109' },
  @{ n = 'favorites';   sql = 'SELECT COUNT(*) FROM doc_favorite';        want = '39' },
  # 2026-09-24: after the corpus grew to 45 docs, count-consistency itself is evidence of a self-consistent seed
  @{ n = 'tag-use-count-consistent'; sql = 'SELECT COUNT(*) FROM doc_tag t WHERE t.use_count <> (SELECT COUNT(*) FROM doc_document_tag_rel r WHERE r.tag_id = t.id)'; want = '0' },
  @{ n = 'favorite-count-consistent'; sql = 'SELECT COUNT(*) FROM doc_document d WHERE d.favorite_count <> (SELECT COUNT(*) FROM doc_favorite f WHERE f.document_id = d.id)'; want = '0' },
  @{ n = 'version-num-consistent'; sql = 'SELECT COUNT(*) FROM doc_document d WHERE d.version_num <> (SELECT MAX(version_num) FROM doc_version v WHERE v.document_id = d.id)'; want = '0' }
)
$seedBad = @()
foreach ($s in $seeds) {
  $got = Scalar $s.sql
  if ($got -ne $s.want) { $seedBad += "$($s.n)=$got(want $($s.want))" }
}
Check 'C14 seed-data-counts' ($seedBad.Count -eq 0) ("checked=" + $seeds.Count + " mismatch=" + $(if ($seedBad.Count) { $seedBad -join ';' } else { '0' }))

# --- C15: password hashes are real BCrypt (not plaintext) --------------------
# single-quoted PS string: keeps the SQL '$2%' pattern literal for BOTH PowerShell and MySQL
$sqlBcrypt = 'SELECT COUNT(*) FROM sys_user WHERE LENGTH(password_hash)<>60 OR password_hash NOT LIKE ''$2%'';'
$badHash = Scalar $sqlBcrypt
Check 'C15 bcrypt-hashes' ($badHash -eq '0') "users without a 60-char BCrypt hash: $badHash (expect 0)"

# --- C16: state machine values are legal -------------------------------------
$badStatus = Scalar "SELECT COUNT(*) FROM doc_document WHERE status NOT IN ('DRAFT','PUBLISHED','ARCHIVED','TRASH');"
$badPermType = Scalar "SELECT COUNT(*) FROM sys_permission WHERE type NOT IN ('DIR','MENU','BUTTON');"
$badUserStatus = Scalar "SELECT COUNT(*) FROM sys_user WHERE status NOT IN ('ACTIVE','LOCKED','DISABLED');"
Check 'C16 enum-values-legal' (($badStatus -eq '0') -and ($badPermType -eq '0') -and ($badUserStatus -eq '0')) "doc.status=$badStatus perm.type=$badPermType user.status=$badUserStatus (expect 0/0/0)"

# --- summary -----------------------------------------------------------------
if (Test-Path -LiteralPath $cnf) { Remove-Item -LiteralPath $cnf -Force }
Write-Host ''
Write-Host ("RESULT: pass={0} fail={1}" -f $script:pass, $script:fails)
if ($script:fails -gt 0) { exit 1 } else { Write-Host 'M2 DATABASE CHECKS: ALL GREEN'; exit 0 }
