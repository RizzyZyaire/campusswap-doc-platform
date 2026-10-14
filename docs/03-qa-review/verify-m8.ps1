# =============================================================================
#  verify-m8.ps1 -- M8 (Elasticsearch full-text search, courseware 10.1) checker
#
#  Three layers, all re-runnable:
#    A. static  : source / config / docs must contain the ES building blocks
#    B. es-live : the running Elasticsearch must expose analysis-ik and split
#                 Chinese words (skipped with a notice when 9200 is closed)
#    C. http    : the running backend must rebuild the index and answer the
#                 three scopes with <mark> highlights (skipped when 10087 is
#                 closed, or when the caller has no credentials)
#
#  Pure ASCII on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI,
#  and CJK bytes on a code line break the parser (see AGENTS.md).
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m8.ps1
#    ... -SkipLive     # static checks only
# =============================================================================
param(
    [switch]$SkipLive,
    [string]$BaseUrl = 'http://localhost:10087',
    [string]$EsUrl = 'http://127.0.0.1:9200',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'Admin@123'
)

$ErrorActionPreference = 'Continue'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Pass = 0
$Fail = 0
$Skip = 0

function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) { $script:Pass++; Write-Host ('  [PASS] ' + $Name) -ForegroundColor Green }
    else { $script:Fail++; Write-Host ('  [FAIL] ' + $Name + $(if ($Detail) { '  -> ' + $Detail } else { '' })) -ForegroundColor Red }
}

function Skip-Check([string]$Name, [string]$Why) {
    $script:Skip++
    Write-Host ('  [SKIP] ' + $Name + '  (' + $Why + ')') -ForegroundColor Yellow
}

function Read-Text([string]$Rel) {
    $p = Join-Path $Repo $Rel
    if (-not (Test-Path $p)) { return '' }
    return [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
}

function Has([string]$Text, [string]$Needle) { return $Text.Contains($Needle) }

function Es-Get([string]$Path) {
    $resp = Invoke-WebRequest -Uri ($EsUrl + $Path) -UseBasicParsing -TimeoutSec 8
    return [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
}

function Es-Post([string]$Path, [string]$Json) {
    $resp = Invoke-WebRequest -Uri ($EsUrl + $Path) -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($Json)) -UseBasicParsing -TimeoutSec 8
    return [Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
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
    try { $obj = $raw | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Raw = ($raw -join ''); Data = $obj; Code = $(if ($obj) { $obj.code } else { 0 }) }
}

Write-Host ''
Write-Host '=== M8 Elasticsearch full-text search ===' -ForegroundColor Cyan
Write-Host ('    repo: ' + $Repo)

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host 'A. static checks' -ForegroundColor Cyan
# -----------------------------------------------------------------------------
$idx = Read-Text 'backend\src\main\java\com\campusswap\document\search\DocumentSearchIndex.java'
Check 'A1 index entity uses @Document + Elasticsearch id field' ((Has $idx '@Document(indexName') -and (Has $idx '@Id'))
Check 'A2 title/summary/content analysed by ik_max_word (index) + ik_smart (search)' `
    ((Has $idx 'ik_max_word') -and (Has $idx 'ik_smart') -and (Has $idx 'analyzer = "ik_max_word"') -and (Has $idx 'searchAnalyzer = "ik_smart"'))
Check 'A3 filter-only fields declared Keyword (no analyzer)' `
    ((Has $idx 'FieldType.Keyword') -and (Has $idx 'FieldType.Text'))

$repoSrc = Read-Text 'backend\src\main\java\com\campusswap\document\search\DocumentSearchRepository.java'
Check 'A4 repository is a plain @Repository over ElasticsearchOperations (no startup connection)' ((Has $repoSrc '@Repository') -and (Has $repoSrc 'ElasticsearchOperations') -and (-not (Has $repoSrc 'extends ElasticsearchRepository')))
Check 'A5 scope decides the must clause (title / content / multi_match)' `
    ((Has $repoSrc 'case TITLE') -and (Has $repoSrc 'case CONTENT') -and (Has $repoSrc 'MultiMatchQuery'))
Check 'A6 field boosts title^3 / summary^2 are declared' ((Has $repoSrc 'title^3') -and (Has $repoSrc 'summary^2'))
Check 'A7 PUBLISHED filter lives in the filter context' (Has $repoSrc 'bool.filter')
Check 'A8 writes are refreshed so a rebuild is immediately searchable' (Has $repoSrc 'refresh()')

$impl = Read-Text 'backend\src\main\java\com\campusswap\document\search\DocumentSearchServiceImpl.java'
Check 'A9 service implements the full rebuild (rebuildAllPublishedDocuments)' ((Has $impl 'rebuildAll') -and (Has $impl 'findPublishedAfter'))
Check 'A10 service implements the MySQL fallback engine' ((Has $impl 'mysqlFallback') -and (Has $impl 'ENGINE_MYSQL'))
Check 'A11 service exposes incremental sync (syncOne / removeOne)' ((Has $impl 'syncOne') -and (Has $impl 'removeOne'))
Check 'A12 scope is passed from the service into the repository query builder' ((Has $impl 'searchRepository.search(keyword, scope') -and (Has $repoSrc 'case TITLE') -and (Has $repoSrc 'case CONTENT'))
Check 'A13 index rebuild uses cursor paging (no deep offset)' ((Has $impl 'findPublishedAfter'))

$hl = Read-Text 'backend\src\main\java\com\campusswap\document\search\SearchTextHighlighter.java'
Check 'A14 highlighter escapes HTML before inserting <mark>' `
    ((Has $hl '&lt;') -and (Has $hl '<mark>') -and (Has $hl 'escape'))

$ctl = Read-Text 'backend\src\main\java\com\campusswap\document\controller\SearchController.java'
Check 'A15 search endpoint GET /api/search/documents' (Has $ctl '/api/search/documents')
Check 'A16 status endpoint GET /api/search/status' (Has $ctl '/api/search/status')
Check 'A17 rebuild endpoint POST /api/search/rebuild' (Has $ctl '/api/search/rebuild')
Check 'A18 endpoints are permission guarded (doc:search / doc:manage)' `
    ((Has $ctl 'doc:search') -and (Has $ctl 'doc:manage'))

$pom = Read-Text 'backend\pom.xml'
Check 'A19 pom declares spring-boot-starter-data-elasticsearch' (Has $pom 'spring-boot-starter-data-elasticsearch')

$dev = Read-Text 'backend\src\main\resources\application-dev.yml'
$demo = Read-Text 'backend\src\main\resources\application-demo.yml'
Check 'A20 dev profile configures elasticsearch uris + short timeouts' `
    ((Has $dev 'elasticsearch:') -and (Has $dev 'uris:') -and (Has $dev 'socket-timeout'))
Check 'A21 demo profile configures the same (package runs without ES)' `
    ((Has $demo 'elasticsearch:') -and (Has $demo 'socket-timeout'))

$svc = Read-Text 'backend\src\main\java\com\campusswap\document\service\impl\DocumentServiceImpl.java'
Check 'A22 document service syncs the index after commit' `
    ((Has $svc 'syncSearchIndexAfterCommit') -and (Has $svc 'TxUtil.afterCommit') -and (Has $svc 'ObjectProvider'))

$api = Read-Text 'frontend\src\api\search.ts'
$typ = Read-Text 'frontend\src\types\search.ts'
$view = Read-Text 'frontend\src\views\document\DocumentListView.vue'
Check 'A23 frontend api layer calls the three search endpoints' `
    ((Has $api 'searchFullText') -and (Has $api 'searchEngineStatus') -and (Has $api 'submitRebuild') -and (Has $api 'getRebuildTask'))
Check 'A24 frontend types model scope / highlight / engine' `
    ((Has $typ 'SearchScope') -and (Has $typ 'titleHighlight') -and (Has $typ 'engine'))
Check 'A25 search page offers the scope selector (title / content / all)' `
    ((Has $view 'SEARCH_SCOPE_TEXT') -and (Has $view 'chooseScope') -and (Has $view 'SCOPES'))
Check 'A26 search page renders server highlights and shows the engine badge' `
    ((Has $view 'v-html="d.titleHtml"') -and (Has $view 'engineName'))

$idxSrc = Read-Text 'backend\src\main\java\com\campusswap\document\search\DocumentSearchIndex.java'
Check 'A28 index creation is explicit and lazy (createIndex=false + first-use creation)' `
    ((Has $idxSrc 'createIndex = false') -and (Has $impl 'ensureIndex') -and (Has $repoSrc 'createIndexIfMissing'))

$doc = Read-Text 'docs\02-design\ES-SEARCH-DESIGN.md'
Check 'A27 design doc ES-SEARCH-DESIGN.md exists and is filled' `
    ((Test-Path (Join-Path $Repo 'docs\02-design\ES-SEARCH-DESIGN.md')) -and ($doc.Length -gt 3000))

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host 'B. Elasticsearch runtime checks' -ForegroundColor Cyan
# -----------------------------------------------------------------------------
$esUp = $false
if (-not $SkipLive) {
    try { $root = Es-Get '/'; $esUp = $true } catch { $esUp = $false }
}
if ($SkipLive) {
    Skip-Check 'B1..B4 Elasticsearch runtime' 'SkipLive'
} elseif (-not $esUp) {
    Skip-Check 'B1..B4 Elasticsearch runtime' ($EsUrl + ' is closed - start it first (bin\elasticsearch.bat or campusswap-start.cmd step 1/6)')
} else {
    $info = $root | ConvertFrom-Json
    Check 'B1 GET / reports a 9.x server' ($info.version.number -like '9.*') ('version=' + $info.version.number)
    $plugins = Es-Get '/_cat/plugins?v'
    Check 'B2 analysis-ik plugin is loaded' ($plugins -match 'analysis-ik')
    $smart = Es-Post '/_analyze' '{"analyzer":"ik_smart","text":"gao bing fa xi tong jia gou"}'
    $cn = Es-Post '/_analyze' '{"analyzer":"ik_smart","text":"\u9ad8\u5e76\u53d1\u7cfb\u7edf\u67b6\u6784"}'
    $tokens = (($cn | ConvertFrom-Json).tokens | ForEach-Object { $_.token }) -join '|'
    Check 'B3 ik_smart splits Chinese into words (not single chars)' `
        (($tokens -match '\u5e76\u53d1') -or ($tokens.Split('|').Count -ge 3)) ('tokens=' + $tokens)
    $maxw = Es-Post '/_analyze' '{"analyzer":"ik_max_word","text":"\u4f01\u4e1a\u7ea7\u9ad8\u5e76\u53d1\u77e5\u8bc6\u5e93"}'
    $tokens2 = (($maxw | ConvertFrom-Json).tokens | ForEach-Object { $_.token })
    Check 'B4 ik_max_word is finer grained than ik_smart' ($tokens2.Count -ge 4) ('tokenCount=' + $tokens2.Count)
}

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host 'C. backend HTTP checks (rebuild + three scopes + highlight)' -ForegroundColor Cyan
# -----------------------------------------------------------------------------
$appUp = $false
if (-not $SkipLive) {
    try {
        $probe = Invoke-WebRequest -Uri ($BaseUrl + '/api/search/status') -UseBasicParsing -TimeoutSec 5
        $appUp = $true
    } catch {
        $code = 0
        try { $code = [int]$_.Exception.Response.StatusCode } catch { $code = 0 }
        $appUp = ($code -eq 401) -or ($code -eq 403)
    }
}
if ($SkipLive) {
    Skip-Check 'C1..C6 backend search API' 'SkipLive'
} elseif (-not $appUp) {
    Skip-Check 'C1..C6 backend search API' ($BaseUrl + ' is not answering - start the backend first')
} else {
    $login = Api 'POST' '/api/auth/login' @{ username = $AdminUser; password = $AdminPass }
    $token = $null
    if ($login.Data -and $login.Data.data) { $token = $login.Data.data.token }
    if (-not $token) {
        Skip-Check 'C1..C6 backend search API' 'login failed - check the credentials'
    } else {
        $st = Api 'GET' '/api/search/status' $null $token
        Check 'C1 GET /api/search/status answers with an engine' `
            (($st.Code -eq 200) -and ($st.Data.data.engine)) ('engine=' + $st.Data.data.engine + ' indexCount=' + $st.Data.data.indexCount)

        # 2026-10-14 M13: this endpoint changed from a sync rebuild to submitting a RabbitMQ task
        $rb = Api 'POST' '/api/search/rebuild' @{} $token
        Check 'C2 POST /api/search/rebuild submits an async task' `
            (($rb.Code -eq 200) -and ($rb.Data.data.status -eq 'SUBMITTED') -and ($rb.Data.data.taskId)) `
            ('status=' + $rb.Data.data.status + ' taskId=' + $rb.Data.data.taskId)
        # wait for the consumer to fill the index, otherwise the search asserts below see nothing
        for ($w = 0; $w -lt 30; $w++) {
            Start-Sleep -Milliseconds 700
            $st2 = Api 'GET' ('/api/search/rebuild/' + $rb.Data.data.taskId) $null $token
            if ($st2.Data.data.status -eq 'SUCCEEDED' -or $st2.Data.data.status -eq 'FAILED') { break }
        }

        # keyword that really exists in the seeded campusswap_db (verified: 7 title hits / 8 content hits)
        $kw = [char]0x652F + [char]0x6559   # U+652F U+6559
        $all = Api 'GET' ('/api/search/documents?keyword=' + [uri]::EscapeDataString($kw) + '&scope=ALL') $null $token
        $hitAll = 0
        if ($all.Data -and $all.Data.data) { $hitAll = $all.Data.data.total }
        Check 'C3 scope=ALL returns hits' (($all.Code -eq 200) -and ($hitAll -ge 1)) ('total=' + $hitAll)

        $first = $null
        if ($hitAll -ge 1) { $first = $all.Data.data.list[0] }
        Check 'C4 hits carry <mark> highlight and a relevance score' `
            (($first -ne $null) -and ($first.titleHighlight -match '<mark>') -and ($null -ne $first.score)) `
            ('titleHighlight=' + $(if ($first) { $first.titleHighlight } else { 'n/a' }))

        $title = Api 'GET' ('/api/search/documents?keyword=' + [uri]::EscapeDataString($kw) + '&scope=TITLE') $null $token
        $content = Api 'GET' ('/api/search/documents?keyword=' + [uri]::EscapeDataString($kw) + '&scope=CONTENT') $null $token
        # the two scopes must hit DIFFERENT documents (comparing totals alone can pass by luck)
        $titleIds = @($title.Data.data.list | ForEach-Object { $_.id }) -join ','
        $contentIds = @($content.Data.data.list | ForEach-Object { $_.id }) -join ','
        Check 'C5 scope=TITLE and scope=CONTENT return different hits' `
            (($title.Code -eq 200) -and ($content.Code -eq 200) -and ($titleIds -ne $contentIds)) `
            ('titleIds=' + $titleIds + ' contentIds=' + $contentIds)

        $bad = Api 'GET' '/api/search/documents?keyword=test&scope=NOPE' $null $token
        Check 'C6 invalid scope is rejected with 400' ($bad.Code -eq 400) ('code=' + $bad.Code)

        # courseware DoD: a title match must outrank a body-only match
        $topFields = @()
        if ($all.Data -and $all.Data.data -and $all.Data.data.list.Count -gt 0) {
            $topFields = $all.Data.data.list[0].matchedFields
        }
        Check 'C7 the best hit matches the title (title boost works)' ($topFields -contains 'title') `
            ('topHit matchedFields=' + ($topFields -join ','))
    }
}

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host ('=== M8 result: PASS ' + $Pass + ' / FAIL ' + $Fail + ' / SKIP ' + $Skip + ' ===') -ForegroundColor Cyan
if ($Fail -gt 0) { exit 1 }
exit 0
