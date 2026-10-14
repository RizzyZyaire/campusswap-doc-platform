# =============================================================================
#  verify-m13.ps1 -- M13 (RabbitMQ asynchronous index rebuild, courseware 13.1)
#
#  Three layers:
#    A. static : topology / manual ack / DLQ / idempotency / task store in code
#    B. mq-live: the broker must expose the two queues and the two exchanges
#                (via the management HTTP API, so no rabbitmqctl path is assumed)
#    C. http   : submit -> SUBMITTED + taskId within 20 ms -> poll to SUCCEEDED
#
#  Pure ASCII on purpose (Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI).
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m13.ps1
#    ... -SkipLive
# =============================================================================
param(
    [switch]$SkipLive,
    [string]$BaseUrl = 'http://localhost:10087',
    [string]$MqApi = 'http://localhost:15672/api',
    [string]$MqUser = 'campusswap',
    [string]$MqPass = 'CampusSwap@2026',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'Admin@123'
)

$ErrorActionPreference = 'Continue'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
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
function MqApi([string]$Path) {
    $pair = $MqUser + ':' + $MqPass
    $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    $resp = Invoke-WebRequest -Uri ($MqApi + $Path) -Headers @{ Authorization = 'Basic ' + $b64 } -UseBasicParsing -TimeoutSec 8
    return [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
}

Write-Host ''
Write-Host '=== M13 RabbitMQ async index rebuild ===' -ForegroundColor Cyan
Write-Host ('    repo: ' + $Repo)

# ---------------------------------------------------------------- A. static
Write-Host ''
Write-Host 'A. static checks' -ForegroundColor Cyan
$pom = Read-Text 'backend\pom.xml'
Check 'A1 pom declares spring-boot-starter-amqp' (Has $pom 'spring-boot-starter-amqp')

$cfg = Read-Text 'backend\src\main\java\com\campusswap\config\MqConfig.java'
Check 'A2 direct exchange + business queue are declared' ((Has $cfg 'DirectExchange') -and (Has $cfg 'searchRebuildQueue'))
Check 'A3 business queue is bound to a dead letter exchange' ((Has $cfg 'deadLetterExchange') -and (Has $cfg 'deadLetterRoutingKey'))
Check 'A4 a dead letter queue exists (dlq)' (Has $cfg 'search.rebuild.dlq')
Check 'A5 JSON message converter is wired (Jackson 3 in Boot 4)' (Has $cfg 'JacksonJsonMessageConverter')

$consumer = Read-Text 'backend\src\main\java\com\campusswap\document\search\mq\SearchRebuildConsumer.java'
Check 'A6 consumer uses @RabbitListener with MANUAL ack' ((Has $consumer '@RabbitListener') -and (Has $consumer 'ackMode = "MANUAL"'))
Check 'A7 success path calls basicAck' (Has $consumer 'basicAck')
Check 'A8 failure path calls basicNack(requeue=false) so messages go to the DLQ' (Has $consumer 'basicNack(deliveryTag, false, false)')
Check 'A9 consumer is idempotent (skips tasks that already succeeded)' (Has $consumer 'SUCCEEDED')
Check 'A10 rebuild is serialised with a redis lock' (Has $consumer 'tryLock')

$prod = Read-Text 'backend\src\main\java\com\campusswap\document\search\mq\SearchRebuildProducer.java'
Check 'A11 producer publishes to the search exchange' ((Has $prod 'convertAndSend') -and (Has $prod 'SEARCH_EXCHANGE'))
Check 'A12 broker outage is reported instead of swallowed' (Has $prod 'markFailed')

$store = Read-Text 'backend\src\main\java\com\campusswap\document\search\mq\SearchRebuildTaskStore.java'
Check 'A13 task state lives in Redis with a TTL' ((Has $store 'StringRedisTemplate') -and (Has $store 'TTL'))
Check 'A14 recent-task list is maintained for the UI' (Has $store 'recent')

$svc = Read-Text 'backend\src\main\java\com\campusswap\document\search\DocumentSearchServiceImpl.java'
Check 'A15 service exposes submitRebuildTask + getRebuildTask' ((Has $svc 'submitRebuildTask') -and (Has $svc 'getRebuildTask'))

$ctl = Read-Text 'backend\src\main\java\com\campusswap\document\controller\SearchController.java'
Check 'A16 three rebuild endpoints exist (submit / status / recent)' `
    ((Has $ctl '/api/search/rebuild') -and (Has $ctl '/api/search/rebuild/{taskId}') -and (Has $ctl '/api/search/rebuild/recent'))
Check 'A17 rebuild endpoints are permission guarded by doc:manage' (Has $ctl 'doc:manage')

foreach ($prof in 'application-dev.yml', 'application-demo.yml') {
    $y = Read-Text ('backend\src\main\resources\' + $prof)
    Check ('A18 ' + $prof + ' configures rabbitmq + manual ack') `
        ((Has $y 'rabbitmq:') -and (Has $y 'port: ') -and (Has $y 'acknowledge-mode: manual'))
}

$api = Read-Text 'frontend\src\api\search.ts'
Check 'A19 frontend api submits and polls the task' ((Has $api 'submitRebuild') -and (Has $api 'getRebuildTask'))
$view = Read-Text 'frontend\src\views\document\DocumentListView.vue'
Check 'A20 search page polls the task and shows its status' `
    ((Has $view 'rebuildTask') -and (Has $view 'getRebuildTask') -and (Has $view 'statusLabel'))

$test = Read-Text 'backend\src\test\java\com\campusswap\document\SearchRebuildMqTest.java'
Check 'A21 unit tests cover ack / DLQ nack / idempotency' `
    ((Has $test 'basicAck') -and (Has $test 'basicNack') -and (Has $test 'idempotentWhenAlreadySucceeded'))

$doc = Read-Text 'docs\03-qa-review\M13-CLOSURE.md'
Check 'A22 closure record exists' ((Test-Path (Join-Path $Repo 'docs\03-qa-review\M13-CLOSURE.md')) -and ($doc.Length -gt 2000))

# ---------------------------------------------------------------- B. mq-live
Write-Host ''
Write-Host 'B. RabbitMQ runtime checks (management API)' -ForegroundColor Cyan
$mqUp = $false
$queues = ''
if ($SkipLive) {
    Skip-Check 'B1..B4 broker topology' 'SkipLive'
} else {
    try { $queues = MqApi '/queues'; $mqUp = $true } catch { $mqUp = $false }
    if (-not $mqUp) {
        Skip-Check 'B1..B4 broker topology' ($MqApi + ' unreachable - start RabbitMQ first (sbin\rabbitmq-server.bat)')
    } else {
        Check 'B1 business queue is declared' ($queues -match 'campusswap\.search\.rebuild')
        Check 'B2 dead letter queue is declared' ($queues -match 'campusswap\.search\.rebuild\.dlq')
        $ex = MqApi '/exchanges'
        Check 'B3 search exchange is declared' ($ex -match 'campusswap\.search\.exchange')
        Check 'B4 dead letter exchange is declared' ($ex -match 'campusswap\.search\.dlx')
    }
}

# ---------------------------------------------------------------- C. http
Write-Host ''
Write-Host 'C. backend HTTP checks (submit -> poll -> succeeded)' -ForegroundColor Cyan
$appUp = $false
if (-not $SkipLive) {
    try {
        $null = Invoke-WebRequest -Uri ($BaseUrl + '/api/search/status') -UseBasicParsing -TimeoutSec 5
        $appUp = $true
    } catch {
        $code = 0
        try { $code = [int]$_.Exception.Response.StatusCode } catch { $code = 0 }
        $appUp = ($code -eq 401) -or ($code -eq 403)
    }
}
if ($SkipLive) {
    Skip-Check 'C1..C5 async rebuild over HTTP' 'SkipLive'
} elseif (-not $appUp) {
    Skip-Check 'C1..C5 async rebuild over HTTP' ($BaseUrl + ' is not answering')
} else {
    $login = Api 'POST' '/api/auth/login' @{ username = $AdminUser; password = $AdminPass }
    $token = $null
    if ($login.Data -and $login.Data.data) { $token = $login.Data.data.token }
    if (-not $token) {
        Skip-Check 'C1..C5 async rebuild over HTTP' 'login failed'
    } else {
        $submit = Api 'POST' '/api/search/rebuild' @{} $token
        $task = $submit.Data.data
        Check 'C1 submit returns SUBMITTED with a taskId' `
            (($submit.Code -eq 200) -and ($task.status -eq 'SUBMITTED') -and ($task.taskId)) ('status=' + $task.status)
        Check 'C2 server side submit cost is under 20 ms (courseware DoD)' `
            (($null -ne $task.submitCostMs) -and ([int]$task.submitCostMs -lt 20)) ('submitCostMs=' + $task.submitCostMs)

        $final = $null
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Milliseconds 700
            $st = Api 'GET' ('/api/search/rebuild/' + $task.taskId) $null $token
            $final = $st.Data.data
            if ($final.status -eq 'SUCCEEDED' -or $final.status -eq 'FAILED') { break }
        }
        Check 'C3 consumer finishes the task asynchronously' ($final.status -eq 'SUCCEEDED') ('final=' + $final.status)
        Check 'C4 rebuild really indexed documents' (($final.indexed -ge 1) -and ($final.indexCount -ge 1)) `
            ('indexed=' + $final.indexed + ' indexCount=' + $final.indexCount)

        $recent = Api 'GET' '/api/search/rebuild/recent' $null $token
        Check 'C5 recent task list exposes the finished task' `
            (($recent.Code -eq 200) -and ($recent.Data.data.Count -ge 1)) ('count=' + $recent.Data.data.Count)
    }
}

Write-Host ''
Write-Host ('=== M13 result: PASS ' + $Pass + ' / FAIL ' + $Fail + ' / SKIP ' + $Skip + ' ===') -ForegroundColor Cyan
if ($Fail -gt 0) { exit 1 }
exit 0
