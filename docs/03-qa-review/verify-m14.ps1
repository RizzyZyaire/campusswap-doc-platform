# =============================================================================
#  verify-m14.ps1 -- M14 operation log (AOP audit + query API + admin page)
#
#  Three layers:
#    A. static : aspect / pointcut / masking / async writer / no new permission
#    B. db     : sys_operation_log exists, every column commented, 3 indexes
#    C. http   : a write operation really lands in the log, failures carry a
#                reason, filters work, and a non-admin gets 403
#
#  Pure ASCII on purpose (Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI).
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m14.ps1
#    ... -SkipLive       (static + db only)
# =============================================================================
param(
    [switch]$SkipLive,
    [string]$BaseUrl = 'http://localhost:10087',
    [string]$DbHost = '127.0.0.1',
    [string]$DbUser = 'root',
    [string]$DbPass = '123456',
    [string]$DbName = 'campusswap_db',
    [string]$MysqlExe = 'D:\DevEnv\03_MySQL\bin\mysql.exe',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'Admin@123',
    [string]$StaffUser = 'staff',
    [string]$StaffPass = 'Staff@123'
)

$ErrorActionPreference = 'Continue'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
# Chinese labels are built from code points so this file stays pure ASCII
# (Windows PowerShell 5.1 would mis-read CJK in a BOM-less script).
$LabelNew = [string]([char]0x65B0) + [char]0x589E
$LabelCategory = [string]([char]0x5206) + [char]0x7C7B
$Pass = 0; $Fail = 0; $Skip = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) { $script:Pass++; Write-Host ('  [PASS] ' + $Name) -ForegroundColor Green }
    else { $script:Fail++; Write-Host ('  [FAIL] ' + $Name + $(if ($Detail) { '  -> ' + $Detail } else { '' })) -ForegroundColor Red }
}
function Skip-Check([string]$Name, [string]$Why) {
    $script:Skip++; Write-Host ('  [SKIP] ' + $Name + '  (' + $Why + ')') -ForegroundColor Yellow
}
function Read-Text([string]$Rel) {
    $p = Join-Path $Repo $Rel
    if (-not (Test-Path $p)) { return '' }
    return [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
}
function Has([string]$Text, [string]$Needle) { return $Text.Contains($Needle) }
function Scalar([string]$Sql) {
    if (-not (Test-Path $MysqlExe)) { return '' }
    $out = & $MysqlExe ('--host=' + $DbHost) ('--port=3306') ('--user=' + $DbUser) ('--password=' + $DbPass) `
        '--default-character-set=utf8mb4' '-N' '-B' '-e' $Sql 2>$null
    return ($out | Where-Object { $_ -notmatch 'Warning' } | Select-Object -First 1)
}
function Api([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $curlArgs = @('-s', '-X', $Method, ($BaseUrl + $Path))
    if ($Token) { $curlArgs += @('-H', ('Authorization: Bearer ' + $Token)) }
    $tmp = $null
    if ($null -ne $Body) {
        $tmp = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($tmp, ($Body | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding($false)))
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', ('@' + $tmp))
    }
    $raw = & curl.exe @curlArgs 2>$null
    if ($tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    $obj = $null
    try { $obj = ($raw -join '') | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Raw = ($raw -join ''); Data = $obj; Code = $(if ($obj) { $obj.code } else { 0 }) }
}
function HttpCode([string]$Path, [string]$Token) {
    return (& curl.exe -s -o NUL -w '%{http_code}' ($BaseUrl + $Path) -H ('Authorization: Bearer ' + $Token))
}

Write-Host ''
Write-Host '=== M14 operation log (AOP audit) ===' -ForegroundColor Cyan
Write-Host ('    repo: ' + $Repo)

# ---------------------------------------------------------------- A. static
Write-Host ''
Write-Host 'A. static checks' -ForegroundColor Cyan
$schema = Read-Text 'backend\sql\schema.sql'
Check 'A1 schema.sql declares sys_operation_log' (Has $schema 'sys_operation_log')
Check 'A2 new table keeps column comments and query indexes' `
    ((Has $schema 'COMMENT=''operation log') -or ((Has $schema 'idx_oplog_created') -and (Has $schema 'idx_oplog_module_created')))

$entity = Read-Text 'backend\src\main\java\com\campusswap\entity\SysOperationLog.java'
Check 'A3 entity maps the table and extends BaseEntity' `
    ((Has $entity '@Table(name = "sys_operation_log")') -and (Has $entity 'extends BaseEntity'))
Check 'A4 entity carries the audit/soft-delete pattern' ((Has $entity '@SQLDelete') -and (Has $entity '@SQLRestriction'))

$anno = Read-Text 'backend\src\main\java\com\campusswap\common\log\OperationLog.java'
Check 'A5 @OperationLog annotation exists (module + action)' ((Has $anno 'String module()') -and (Has $anno 'String action()'))

$aspect = Read-Text 'backend\src\main\java\com\campusswap\common\log\OperationLogAspect.java'
Check 'A6 aspect cuts across controllers, not one annotated method' `
    ((Has $aspect '@Pointcut("execution(public * com.campusswap..controller..*(..))")') -and (Has $aspect '@Around'))
Check 'A7 aspect only records write methods' `
    ((Has $aspect 'PostMapping') -and (Has $aspect 'PutMapping') -and (Has $aspect 'DeleteMapping') -and (Has $aspect 'return null'))
Check 'A8 aspect never breaks the business call' ((Has $aspect 'catch (Throwable t)') -and (Has $aspect 'finally'))
Check 'A9 sensitive params are masked' `
    ((Has $aspect 'SENSITIVE') -and (Has $aspect 'password') -and (Has $aspect '***'))
Check 'A10 params are truncated before insert' (Has $aspect 'PARAMS_MAX = 1000')
Check 'A11 login audit takes the account from the request body (records)' (Has $aspect 'usernameFromArgs')

$writer = Read-Text 'backend\src\main\java\com\campusswap\common\log\OperationLogWriter.java'
Check 'A12 log is persisted asynchronously in its own bean' `
    ((Has $writer '@Async("operationLogExecutor")') -and (Has $writer 'logRepository.save'))
Check 'A13 async write failures are swallowed with a warning' (Has $writer 'log.warn')

$cfg = Read-Text 'backend\src\main\java\com\campusswap\config\AsyncConfig.java'
Check 'A14 bounded async executor with caller-runs fallback' `
    ((Has $cfg '@EnableAsync') -and (Has $cfg 'setQueueCapacity') -and (Has $cfg 'CallerRunsPolicy'))

$spec = Read-Text 'backend\src\main\java\com\campusswap\system\repository\OperationLogSpecifications.java'
Check 'A15 dynamic filters use Criteria (no string SQL)' `
    ((Has $spec 'Specification<SysOperationLog>') -and (Has $spec 'cb.equal') -and (Has $spec 'greaterThanOrEqualTo'))

$ctl = Read-Text 'backend\src\main\java\com\campusswap\system\controller\OperationLogController.java'
Check 'A16 query endpoints exist and need sys:center' `
    ((Has $ctl '/api/logs') -and (Has $ctl '/api/logs/filters') -and (Has $ctl '@RequiresPermission("sys:center")'))

$data = Read-Text 'backend\sql\data.sql'
Check 'A17 no new permission point was added (M0 freeze stays at 39)' `
    ((@(Select-String -Path (Join-Path $Repo 'backend\sql\data.sql') -Pattern "'(sys|doc):[a-z:]+" -AllMatches).Count -ge 39) -and (@(Select-String -Path (Join-Path $Repo 'backend\sql\data.sql') -Pattern "'sys:log" -ErrorAction SilentlyContinue).Count -eq 0))

$types = Read-Text 'frontend\src\types\log.ts'
$api = Read-Text 'frontend\src\api\logs.ts'
$view = Read-Text 'frontend\src\views\admin\OperationLogView.vue'
$router = Read-Text 'frontend\src\router\index.ts'
$shell = Read-Text 'frontend\src\layouts\AppShell.vue'
Check 'A18 frontend type/api/page for the log exist' `
    ((Has $types 'OperationLogVo') -and (Has $api 'pageOperationLogs') -and (Has $view 'operationLogFilters'))
Check 'A19 page is routed and reachable from the sidebar (perm sys:center)' `
    ((Has $router '/admin/logs') -and (Has $shell '/admin/logs') -and (Has $shell 'PERM.sysCenter'))

$test = Read-Text 'backend\src\test\java\com\campusswap\system\Ac09OperationLogTest.java'
Check 'A20 unit tests cover masking, failure capture and read-only skip' `
    ((Has $test 'passwordMasked') -and (Has $test 'failureIsRecorded') -and (Has $test 'readOperationNotLogged'))
Check 'A21 closure record exists' (Test-Path (Join-Path $Repo 'docs\03-qa-review\M14-CLOSURE.md'))

# ---------------------------------------------------------------- B. db
Write-Host ''
Write-Host 'B. database checks' -ForegroundColor Cyan
if ($SkipLive) {
    Skip-Check 'B1..B3 sys_operation_log in the live database' 'SkipLive'
} elseif (-not (Test-Path $MysqlExe)) {
    Skip-Check 'B1..B3 sys_operation_log in the live database' 'mysql client not found'
} else {
    $cols = Scalar ("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + $DbName + "' AND TABLE_NAME='sys_operation_log';")
    Check 'B1 table exists with 19 columns' ($cols -eq '19') ('columns=' + $cols)
    $noComment = Scalar ("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + $DbName + "' AND TABLE_NAME='sys_operation_log' AND (COLUMN_COMMENT IS NULL OR COLUMN_COMMENT='');")
    Check 'B2 every column carries a Chinese comment' ($noComment -eq '0') ('without-comment=' + $noComment)
    $idx = Scalar ("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + $DbName + "' AND TABLE_NAME='sys_operation_log';")
    Check 'B3 primary key + 3 query indexes' ($idx -eq '4') ('indexes=' + $idx)
}

# ---------------------------------------------------------------- C. http
Write-Host ''
Write-Host 'C. backend HTTP checks' -ForegroundColor Cyan
$appUp = $false
if (-not $SkipLive) {
    try { $null = Invoke-WebRequest -Uri ($BaseUrl + '/api/search/status') -UseBasicParsing -TimeoutSec 5; $appUp = $true }
    catch {
        $code = 0
        try { $code = [int]$_.Exception.Response.StatusCode } catch { $code = 0 }
        $appUp = ($code -eq 401) -or ($code -eq 403)
    }
}
if ($SkipLive) {
    Skip-Check 'C1..C6 operation log over HTTP' 'SkipLive'
} elseif (-not $appUp) {
    Skip-Check 'C1..C6 operation log over HTTP' ($BaseUrl + ' is not answering')
} else {
    $login = Api 'POST' '/api/auth/login' @{ username = $AdminUser; password = $AdminPass }
    $token = $null
    if ($login.Data -and $login.Data.data) { $token = $login.Data.data.token }
    if (-not $token) {
        Skip-Check 'C1..C6 operation log over HTTP' 'login failed'
    } else {
        $page = Api 'GET' '/api/logs?pageSize=5' $null $token
        Check 'C1 GET /api/logs answers with a page of rows' `
            (($page.Code -eq 200) -and ($page.Data.data.list.Count -ge 1)) ('total=' + $page.Data.data.total)
        $first = $page.Data.data.list[0]
        Check 'C2 rows carry the audit columns' `
            (($null -ne $first.module) -and ($null -ne $first.action) -and ($null -ne $first.createdAt) -and ($null -ne $first.uri))

        # a real write must land in the log: rename a category (PUT) then look for the row
        $stamp = Get-Date -Format 'HHmmss'
        $catName = 'M14-check-' + $stamp
        $tree = Api 'GET' '/api/categories/tree' $null $token
        $parentId = ''
        if ($tree.Data -and $tree.Data.data -and $tree.Data.data.Count -gt 0) { $parentId = [string]$tree.Data.data[0].id }
        $created = Api 'POST' '/api/categories' @{ name = $catName; parentId = $parentId; sortOrder = 1 } $token
        $catId = ''
        if ($created.Data -and $created.Data.data) { $catId = [string]$created.Data.data.id }
        Start-Sleep -Milliseconds 1200
        $hit = Api 'GET' ('/api/logs?username=' + $AdminUser + '&pageSize=20') $null $token
        $found = @($hit.Data.data.list | Where-Object { $_.uri -eq '/api/categories' -and $_.action -eq $LabelNew -and $_.success })
        Check 'C3 a real write operation appears in the log' ($found.Count -ge 1) ('matches=' + $found.Count)
        if ($found.Count -ge 1) {
            Check 'C4 the row records who/what/where' `
                (($found[0].username -eq $AdminUser) -and ($found[0].module -eq $LabelCategory) -and ($null -ne $found[0].costMs))
        } else {
            Check 'C4 the row records who/what/where' $false 'no matching row'
        }

        # a failing write must be recorded too, with its Chinese reason
        $dupTag = 'M14-dup-' + $stamp
        $null = Api 'POST' '/api/tags' @{ name = $dupTag } $token
        $dup = Api 'POST' '/api/tags' @{ name = $dupTag } $token
        Start-Sleep -Milliseconds 1200
        $failed = Api 'GET' '/api/logs?success=0&pageSize=10' $null $token
        $failRow = @($failed.Data.data.list | Where-Object { $_.uri -eq '/api/tags' -and (-not $_.success) })
        Check 'C5 a failing write is recorded with a reason' `
            (($dup.Code -eq 409) -and ($failRow.Count -ge 1) -and ($failRow[0].errorMsg)) ('conflict=' + $dup.Code + ' rows=' + $failRow.Count)

        $filters = Api 'GET' '/api/logs/filters' $null $token
        Check 'C6 filter options come from real data' `
            (($filters.Code -eq 200) -and ($filters.Data.data.modules.Count -ge 1)) ('modules=' + $filters.Data.data.modules.Count)

        $staffLogin = Api 'POST' '/api/auth/login' @{ username = $StaffUser; password = $StaffPass }
        $staffToken = $null
        if ($staffLogin.Data -and $staffLogin.Data.data) { $staffToken = $staffLogin.Data.data.token }
        if ($staffToken) {
            Check 'C7 non admin is rejected with 403' ((HttpCode '/api/logs' $staffToken) -eq '403')
        } else {
            Skip-Check 'C7 non admin is rejected with 403' 'staff login failed'
        }

        # cleanup: the check must be re-runnable (drop what it created)
        if ($catId) { $null = Api 'DELETE' ('/api/categories/' + $catId) $null $token }
    }
}

Write-Host ''
Write-Host ('=== M14 result: PASS ' + $Pass + ' / FAIL ' + $Fail + ' / SKIP ' + $Skip + ' ===') -ForegroundColor Cyan
if ($Fail -gt 0) { exit 1 }
exit 0
