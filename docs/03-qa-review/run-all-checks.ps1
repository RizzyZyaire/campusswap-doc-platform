# =============================================================================
#  run-all-checks.ps1 -- one command for the whole acceptance battery
#
#  Why: the checkers have a mandated order (documented in TEST_CHECKLIST.md),
#  because the HTTP ones MUTATE campusswap_db: reload -> static/DB-only ->
#  HTTP -> reload. Since 2026-09-24 the HTTP checkers reload the database
#  themselves (verify-m3-http / verify-m4-http / verify-m6-http, each with a
#  -NoReload escape hatch), so this script mainly (a) keeps the order, (b) prints
#  one summary table, (c) guarantees the database ends pristine.
#
#  Prerequisites: backend on http://localhost:10087 started through
#  D:\DevEnv\scripts\campusswap-backend.cmd (the SQL-count assertions read the
#  app log it tees), MySQL 8 + Redis running, JAVA_HOME -> JDK 17 for mvnw.
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs/03-qa-review/run-all-checks.ps1
#    ... -SkipHttp        # static + DB-only checks (no backend needed)
#    ... -OnlyHttp        # HTTP checks only
#    ... -DbPassword <root password>   # default: $env:MYSQL_ROOT_PASSWORD, else local dev default
#
#  Exit code = number of failed checkers (0 = everything green)
#  Pure ASCII on purpose (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
# =============================================================================
param(
    [switch]$SkipHttp,
    [switch]$OnlyHttp,
    [string]$AppLog = 'D:\DevEnv\logs\campusswap-app.log',
    [string]$DbPassword = ''
)

$ErrorActionPreference = 'Continue'
$qa = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $qa '..\..')).Path

if ([string]::IsNullOrEmpty($DbPassword)) { $DbPassword = $env:MYSQL_ROOT_PASSWORD }
if ([string]::IsNullOrEmpty($DbPassword)) { $DbPassword = '123456' }   # local dev default (see D:\DevEnv\README.md)
$env:MYSQL_ROOT_PASSWORD = $DbPassword
if ([string]::IsNullOrEmpty($env:JAVA_HOME)) { $env:JAVA_HOME = 'D:\DevEnv\02_JDK\jdk-17.0.5' }

$results = @()

function Invoke-Checker {
    param([string]$Label, [string]$Script, [string[]]$Extra = @())
    $path = Join-Path $qa $Script
    if (-not (Test-Path $path)) {
        Write-Host ('  !! ' + $Label + ': script not found: ' + $Script)
        $script:results += [pscustomobject]@{ Checker = $Label; Result = 'MISSING'; Exit = -1 }
        return
    }
    Write-Host ''
    Write-Host ('>>> ' + $Label + '  (' + $Script + ')')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $path @Extra 2>&1
    $code = $LASTEXITCODE
    $sw.Stop()
    $summary = @($out | Select-String -Pattern 'RESULT' | ForEach-Object { $_.Line.Trim() })
    if ($summary.Count -eq 0) { $summary = @($out | Select-Object -Last 2) }
    Write-Host ('    ' + ($summary -join ' | '))
    $fails = @($out | Select-String -Pattern '\[FAIL\]' | ForEach-Object { $_.Line.Trim() })
    foreach ($f in $fails) { Write-Host ('    ' + $f) }
    $script:results += [pscustomobject]@{
        Checker = $Label
        Result  = ($summary -join ' | ')
        Exit    = $code
        Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }
}

Write-Host '============================================================='
Write-Host ' CampusSwap full acceptance battery'
Write-Host (' repo    : ' + $repo)
Write-Host (' app log : ' + $AppLog)
Write-Host '============================================================='

if (-not $OnlyHttp) {
    Write-Host ''
    Write-Host '--- phase 1/3: reload seed database ---'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $qa 'reload-db.ps1') 2>&1 |
        Select-String -Pattern 'documents=|versions=|favorites=|reload done' | ForEach-Object { Write-Host ('    ' + $_.Line.Trim()) }

    Write-Host ''
    Write-Host '--- phase 2/3: static + DB-only checkers ---'
    Invoke-Checker 'm0 requirement freeze' 'verify-m0.ps1'
    Invoke-Checker 'm1 design freeze' 'verify-m1.ps1'
    Invoke-Checker 'api spec' 'verify-api-spec.ps1'
    Invoke-Checker 'm2 database' 'verify-m2.ps1'
    Invoke-Checker 'db deep' 'verify-db-deep.ps1'
    Invoke-Checker 'm3 backend/rbac' 'verify-m3.ps1'
    Invoke-Checker 'm4 document domain' 'verify-m4.ps1'
    Invoke-Checker 'm5 frontend' 'verify-m5.ps1'
    Invoke-Checker 'm6 tests/review' 'verify-m6.ps1'
}

if (-not $SkipHttp) {
    Write-Host ''
    Write-Host '--- phase 3/3: HTTP checkers (they reload the DB before and after themselves) ---'
    Invoke-Checker 'm3-http system endpoints' 'verify-m3-http.ps1'
    Invoke-Checker 'm4-http document endpoints' 'verify-m4-http.ps1' @('-AppLog', $AppLog)
    Invoke-Checker 'm6-http exception paths' 'verify-m6-http.ps1'
}

Write-Host ''
Write-Host '--- restore seed database (no manual step) ---'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $qa 'reload-db.ps1') 2>&1 |
    Select-String -Pattern 'documents=|versions=|favorites=|reload done' | ForEach-Object { Write-Host ('    ' + $_.Line.Trim()) }

$failed = @($results | Where-Object { $_.Exit -ne 0 })
Write-Host ''
Write-Host '============================================================='
Write-Host ' SUMMARY'
Write-Host '============================================================='
foreach ($r in $results) {
    $flag = 'PASS'; if ($r.Exit -ne 0) { $flag = 'FAIL' }
    Write-Host (' [{0}] {1,-28} {2,6}s  {3}' -f $flag, $r.Checker, $r.Seconds, $r.Result)
}
Write-Host ''
Write-Host (' RESULT: checkers=' + $results.Count + ' failed=' + $failed.Count)
if ($failed.Count -gt 0) {
    foreach ($f in $failed) { Write-Host ('   failed: ' + $f.Checker) }
}
Write-Host '============================================================='
exit $failed.Count
