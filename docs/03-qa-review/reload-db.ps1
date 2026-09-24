# =============================================================================
#  reload-db.ps1 -- CampusSwap database reload helper (M6 evidence hygiene)
#
#  What it does: replays backend/sql/schema.sql (14 tables, 20 indexes) and then
#  backend/sql/data.sql (45 documents / 20 tags / 99 versions / 39 favorites).
#  Both files are idempotent: schema.sql drops+creates every table, data.sql
#  truncates before inserting explicit ids.
#
#  Why a script: TEST_CHECKLIST.md documents a strict checker run order
#  (reload -> static checkers -> HTTP checkers -> reload again), because the
#  HTTP checkers mutate the database. This keeps that step one command long and
#  byte-for-byte repeatable.
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/reload-db.ps1
#    ... -Mysql <path to mysql.exe> -DbUser root -DbPassword 123456
#
#  Pure ASCII on purpose (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#  NOTE: the password is passed to the client through MYSQL_PWD for THIS process
#  only. It is deliberately NOT exported as DB_PASSWORD: the running backend
#  resolves its own datasource password from DB_PASSWORD, so exporting root's
#  password would break the app account (documented in TEST_CHECKLIST.md).
# =============================================================================
param(
    [string]$Mysql = 'D:\DevEnv\03_MySQL\bin\mysql.exe',
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [string]$SqlDir = ''
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..')).Path
if ([string]::IsNullOrEmpty($SqlDir)) { $SqlDir = Join-Path $repo 'backend\sql' }
$schema = Join-Path $SqlDir 'schema.sql'
$data = Join-Path $SqlDir 'data.sql'

foreach ($f in @($schema, $data)) {
    if (-not (Test-Path $f)) { throw "missing SQL file: $f" }
}
if (-not (Test-Path $Mysql)) { throw "mysql client not found: $Mysql" }

$env:MYSQL_PWD = $DbPassword

function Invoke-SqlFile {
    param([string]$File, [string[]]$Extra = @())
    $posix = $File.Replace('\', '/')
    # NOTE: the user flag must be ONE token "-uroot". Writing "-$DbUser" yields
    # "-root" (dash + value), which mysql silently ignores as an option cluster
    # and then falls back to the default user "ODBC" -> ERROR 1045.
    $mysqlArgs = @(('-u' + $DbUser), '--default-character-set=utf8mb4') + $Extra + @('-e', "source $posix")
    & $Mysql @mysqlArgs
    if ($LASTEXITCODE -ne 0) { throw "mysql failed on $File (exit $LASTEXITCODE)" }
}

Write-Host '[1/3] schema.sql (DROP + CREATE 14 tables / 20 indexes) ...'
Invoke-SqlFile -File $schema
Write-Host '[2/3] data.sql (TRUNCATE + seed data) ...'
Invoke-SqlFile -File $data -Extra @('campusswap_db')
Write-Host '[3/3] row counts ...'
& $Mysql ('-u' + $DbUser) -N -B -e @"
SELECT CONCAT('tables=', COUNT(*)) FROM information_schema.tables WHERE table_schema='campusswap_db';
SELECT CONCAT('indexes=', COUNT(*)) FROM information_schema.statistics WHERE table_schema='campusswap_db';
SELECT CONCAT('documents=', COUNT(*)) FROM campusswap_db.doc_document;
SELECT CONCAT('published=', COUNT(*)) FROM campusswap_db.doc_document WHERE status='PUBLISHED' AND deleted=0;
SELECT CONCAT('versions=', COUNT(*)) FROM campusswap_db.doc_version;
SELECT CONCAT('tag_rels=', COUNT(*)) FROM campusswap_db.doc_document_tag_rel;
SELECT CONCAT('favorites=', COUNT(*)) FROM campusswap_db.doc_favorite;
SELECT CONCAT('permissions=', COUNT(*)) FROM campusswap_db.sys_permission;
"@
if ($LASTEXITCODE -ne 0) { throw "self-check query failed (exit $LASTEXITCODE)" }
Write-Host 'reload done.'
