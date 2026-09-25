# =============================================================================
#  CampusSwap M6 HTTP check -- exception paths (T6.3) + deep-paging guard (T6.6)
#
#  Covers the four paths the master plan names explicitly
#    (403 IDOR / 409 state conflict / 400 invalid param / idempotent favorite)
#  plus 401 unauthenticated, 404 missing resource, AC-01.2 user-enumeration
#  defence and AC-08.3 permission-tree cycle message.
#
#  Design notes
#   - pure ASCII source: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI,
#     so every Chinese expectation is built from code points through Cn().
#   - requests go through curl.exe so 4xx bodies are captured instead of
#     throwing (Invoke-WebRequest would need nested try/catch per call).
#   - reads only, except: creating nothing, and the favorite toggle pair which
#     is restored to its original state inside the same run (idempotency IS the
#     assertion). Safe to re-run; no reseed required.
#   - the permission-cycle check also locks the M6 behaviour change: before M6
#     the hierarchy rule ran first and answered "level not allowed" instead of
#     the AC-08.3 wording, so this check fails against the pre-M6 revision.
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File verify-m6-http.ps1
#  Exit code = number of failed checks (0 = all pass)
# =============================================================================
param(
    [string]$BaseUrl = 'http://localhost:10087',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'Admin@123',
    [string]$DocAdminUser = 'docadmin',
    [string]$DocAdminPass = 'Doc@123456',
    [string]$StaffUser = 'staff',
    [string]$StaffPass = 'Staff@123',
    [switch]$NoReload
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()
$script:Tmp = Join-Path $env:TEMP 'campusswap-m6-http'
if (-not (Test-Path $script:Tmp)) { New-Item -ItemType Directory -Path $script:Tmp -Force | Out-Null }
$script:Seq = 0
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# --- database hygiene --------------------------------------------------------
# Fixtures come from the seed database (staff's own published document, somebody
# else's published document, a trashed one), so the run starts from a freshly
# reloaded database and restores it afterwards. That also makes this checker
# survive a previous verify-m4-http run, which resets staff's password.
# Pass -NoReload when you deliberately want to inspect the post-run state.
$script:ReloadScript = Join-Path $PSScriptRoot 'reload-db.ps1'
function Invoke-DbReload {
    param([string]$When)
    if ($NoReload) { Write-Host ('  [db] reload (' + $When + ') skipped: -NoReload'); return }
    if (-not (Test-Path $script:ReloadScript)) { Write-Host ('  [db] reload script not found: ' + $script:ReloadScript); return }
    Write-Host ('  [db] reloading campusswap_db (' + $When + ') ...')
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $script:ReloadScript 2>&1
    $code = $LASTEXITCODE
    $counts = @($out | Select-String -Pattern 'documents=|versions=|favorites=' | ForEach-Object { $_.Line.Trim() })
    Write-Host ('  [db] reload exit=' + $code + ' ; ' + ($counts -join ' ; '))
}

function Cn {
    param([string]$Codes)
    return (-join ($Codes.Split(',') | ForEach-Object { [char][int]('0x' + $_.Trim()) }))
}

function Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null)
    $script:Seq++
    $respFile = Join-Path $script:Tmp ('r-' + $script:Seq + '.json')
    # always start from a clean slate: if curl fails (dead backend, reset), the
    # response file simply will not exist, so we can never read a STALE body from
    # an earlier run and report it as this run's result (hit 2026-09-25).
    if (Test-Path -LiteralPath $respFile) { Remove-Item -LiteralPath $respFile -Force }
    $curlArgs = @('-s', '-o', $respFile, '-w', '%{http_code}', '-X', $Method)
    if ($Token) { $curlArgs += @('-H', ('Authorization: Bearer ' + $Token)) }
    if ($Body -ne $null) {
        $bodyFile = Join-Path $script:Tmp ('b-' + $script:Seq + '.json')
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
        [System.IO.File]::WriteAllText($bodyFile, $json, $Utf8NoBom)
        $curlArgs += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $bodyFile))
    }
    $status = (& curl.exe @curlArgs ($BaseUrl + $Path)) -join ''
    $text = ''
    if (Test-Path $respFile) { $text = [System.IO.File]::ReadAllText($respFile, [System.Text.Encoding]::UTF8) }
    $data = $null
    if ($text) { try { $data = $text | ConvertFrom-Json } catch { $data = $null } }
    return [pscustomobject]@{ Status = [int]$status; Body = $text; Data = $data }
}

function Check {
    param([string]$Label, $Actual, $Expected)
    if ($Actual -eq $Expected) {
        $script:Pass++; Write-Host ('  [PASS] ' + $Label + ' = ' + $Actual)
    } else {
        $script:Fail++; $script:Failures += $Label
        Write-Host ('  [FAIL] ' + $Label + ' : expected [' + $Expected + '] actual [' + $Actual + ']')
    }
}

function Check-True {
    param([string]$Label, $Condition, [string]$Detail = '')
    if ($Condition) { $script:Pass++; Write-Host ('  [PASS] ' + $Label + ' ' + $Detail) }
    else { $script:Fail++; $script:Failures += $Label; Write-Host ('  [FAIL] ' + $Label + ' ' + $Detail) }
}

function Login {
    param([string]$User, [string]$Pass)
    $r = Api -Method POST -Path '/api/auth/login' -Body (@{ username = $User; password = $Pass })
    if ($r.Status -ne 200) { throw ('login failed for ' + $User + ': HTTP ' + $r.Status + ' ' + $r.Body) }
    return $r.Data.data
}

# --- expected Chinese messages, as code points (source stays ASCII) ----------
$MsgBadCredentials = Cn '7528,6237,540d,6216,5bc6,7801,9519,8bef'                                  # user name or password wrong
$MsgNoPermission = Cn '65e0,6743,9650,6267,884c,8be5,64cd,4f5c'                                    # no permission
$MsgBadTitle = Cn '6587,6863,6807,9898,4e0d,80fd,4e3a,7a7a,4e14,4e0d,8d85,8fc7,31,32,38,5b57'       # title blank or > 128
$MsgPageTooDeep = Cn '9875,7801,4e0d,80fd,8d85,8fc7,31,30,30,ff0c,8bf7,7f29,5c0f,7b5b,9009,8303,56f4,540e,518d,8bd5'  # pageNum > 100
$MsgCycle = Cn '4e0d,80fd,5c06,8282,70b9,79fb,52a8,5230,5176,5b50,8282,70b9,4e0b'                    # cannot move under own child
$MsgTrashPublish = Cn '56de,6536,7ad9,6587,6863,9700,5148,6062,590d,4e3a,8349,7a3f'                 # restore the trashed doc first
$MsgNoPermissionEdit = Cn '65e0,6743,9650,4fee,6539,8be5,6587,6863'                               # no permission to edit this doc

Write-Host '============================================================='
Write-Host ' CampusSwap M6 HTTP check: exception paths (T6.3)'
Write-Host (' target: ' + $BaseUrl)
Write-Host '============================================================='
Invoke-DbReload 'before run'

# Fail fast (and readably) when the dev server is down: without this, curl prints
# 000 for every call and the run reports dozens of confusing assertion failures.
# An unauthenticated /api/stats/overview answers 401 on a healthy backend.
$probe = Api -Method GET -Path '/api/stats/overview'
if ($probe.Status -ne 401) {
    Write-Host ('  [FATAL] backend not reachable at ' + $BaseUrl + ' (HTTP ' + $probe.Status + ')')
    Write-Host '          start it with D:\DevEnv\scripts\campusswap-backend.cmd, then re-run'
    exit 99
}

$admin = Login $AdminUser $AdminPass
$docadmin = Login $DocAdminUser $DocAdminPass
$staff = Login $StaffUser $StaffPass
$staffId = [string]$staff.userInfo.id
$adminToken = $admin.token
$staffToken = $staff.token

# ---- pick fixtures dynamically (never hard-code seed ids) -------------------
$search = Api -Method GET -Path '/api/documents?pageNum=1&pageSize=50' -Token $staffToken
$otherPub = @($search.Data.data.list | Where-Object { ([string]$_.authorId -ne $staffId) -and $_.status -eq 'PUBLISHED' })[0]
$mine = Api -Method GET -Path '/api/documents/mine?pageNum=1&pageSize=50' -Token $staffToken
$ownPub = @($mine.Data.data.list | Where-Object { $_.status -eq 'PUBLISHED' })[0]
$trash = Api -Method GET -Path '/api/documents/trash?pageNum=1&pageSize=50' -Token $staffToken
$ownTrash = @($trash.Data.data.list)[0]
$mineTotalBefore = $mine.Data.data.total
if (-not $otherPub) { throw 'fixture missing: no published document owned by somebody else' }
if (-not $ownPub) { throw 'fixture missing: staff owns no published document' }
if (-not $ownTrash) { throw 'fixture missing: staff trash is empty' }
Write-Host (' fixtures: otherPub=' + $otherPub.id + ' ownPub=' + $ownPub.id + ' ownTrash=' + $ownTrash.id + ' staffId=' + $staffId)

# ============================ A. AC-01.2 user enumeration ====================
Write-Host ''
Write-Host 'A. AC-01.2 wrong password vs unknown user (401, same wording)'
$a1 = Api -Method POST -Path '/api/auth/login' -Body (@{ username = $StaffUser; password = 'WrongPass1' })
$a2 = Api -Method POST -Path '/api/auth/login' -Body (@{ username = 'nobody0001'; password = 'WrongPass1' })
Check 'A1.login.wrong-password.http' $a1.Status 401
Check 'A2.login.unknown-user.http' $a2.Status 401
Check 'A3.login.wrong-password.code' $a1.Data.code 401
Check 'A4.login.same-message' ([string]$a1.Data.message) $MsgBadCredentials
Check-True 'A5.login.message-not-distinguishable' ([string]$a1.Data.message -eq [string]$a2.Data.message) ('[' + $a1.Data.message + ']')

# ============================ B. 401 unauthenticated ========================
Write-Host ''
Write-Host 'B. missing token (401, no side effect)'
$b1 = Api -Method GET -Path '/api/stats/overview'
$b2 = Api -Method POST -Path '/api/documents' -Body (@{ title = 'M6 no-token probe'; categoryId = '1' })
$mineAfter = Api -Method GET -Path '/api/documents/mine?pageNum=1&pageSize=50' -Token $staffToken
Check 'B1.no-token.stats.http' $b1.Status 401
Check 'B2.no-token.create.http' $b2.Status 401
Check 'B3.no-token.create.no-insert' $mineAfter.Data.data.total $mineTotalBefore

# ============================ C. 403 IDOR / permission ======================
Write-Host ''
Write-Host 'C. 403: editing somebody else''s document and permission-gated reads'
$beforeDetail = Api -Method GET -Path ('/api/documents/' + $otherPub.id) -Token $staffToken
$before = $beforeDetail.Data.data
$c1 = Api -Method PUT -Path ('/api/documents/' + $otherPub.id) -Token $staffToken -Body (@{
    id = [string]$otherPub.id; versionNum = $before.versionNum; title = 'M6 IDOR probe'
    summary = [string]$before.summary; contentMd = [string]$before.contentMd; categoryId = [string]$before.categoryId
})
$afterDetail = Api -Method GET -Path ('/api/documents/' + $otherPub.id) -Token $staffToken
$after = $afterDetail.Data.data
Check 'C1.edit-others.http' $c1.Status 403
Check 'C2.edit-others.code' $c1.Data.code 403
Check 'C3.edit-others.message' ([string]$c1.Data.message) $MsgNoPermissionEdit
Check-True 'C4.edit-others.row-unchanged' (([string]$before.title -eq [string]$after.title) -and ([string]$before.contentMd -eq [string]$after.contentMd) -and ([string]$before.versionNum -eq [string]$after.versionNum)) ('v' + $before.versionNum + '->v' + $after.versionNum)
$c2 = Api -Method GET -Path '/api/review/documents?pageNum=1&pageSize=10' -Token $staffToken
$c3 = Api -Method GET -Path '/api/users?pageNum=1&pageSize=10' -Token $staffToken
$c4 = Api -Method GET -Path '/api/permissions/tree' -Token $staffToken
Check 'C5.review-list.staff.http' $c2.Status 403
Check 'C6.users.staff.http' $c3.Status 403
Check 'C7.permissions.staff.http' $c4.Status 403

# ============================ D. 409 state conflict ========================
Write-Host ''
Write-Host 'D. 409: publishing an already published / trashed document'
$d1 = Api -Method POST -Path ('/api/documents/' + $ownPub.id + '/publish') -Token $staffToken
$d2 = Api -Method POST -Path ('/api/documents/' + $ownTrash.id + '/publish') -Token $staffToken
Check 'D1.publish-published.http' $d1.Status 409
Check 'D2.publish-published.code' $d1.Data.code 409
Check 'D3.publish-trashed.http' $d2.Status 409
Check 'D4.publish-trashed.message' ([string]$d2.Data.message) $MsgTrashPublish
$ownPubAfter = (Api -Method GET -Path ('/api/documents/' + $ownPub.id) -Token $staffToken).Data.data
Check 'D5.publish-published.no-version-bump' ([string]$ownPubAfter.versionNum) ([string]$ownPub.versionNum)

# ============================ E. 400 invalid parameter ====================
Write-Host ''
Write-Host 'E. 400: validation messages and deep-paging guard'
$e1 = Api -Method POST -Path '/api/documents' -Token $staffToken -Body (@{ title = ''; summary = 'x'; contentMd = 'y'; categoryId = '1' })
$longTitle = ('T' * 129)
$e2 = Api -Method POST -Path '/api/documents' -Token $staffToken -Body (@{ title = $longTitle; summary = 'x'; contentMd = 'y'; categoryId = '1' })
$e3 = Api -Method GET -Path '/api/documents?pageNum=101&pageSize=10' -Token $staffToken
$e4 = Api -Method GET -Path '/api/documents?pageNum=0&pageSize=10' -Token $staffToken
$e5 = Api -Method GET -Path '/api/documents?pageNum=1&pageSize=101' -Token $staffToken
$e6 = Api -Method GET -Path '/api/documents/mine?pageNum=1&pageSize=10&status=TRASH' -Token $staffToken
$mineAfterBad = Api -Method GET -Path '/api/documents/mine?pageNum=1&pageSize=50' -Token $staffToken
Check 'E1.empty-title.http' $e1.Status 400
Check 'E2.empty-title.message' ([string]$e1.Data.message) $MsgBadTitle
Check 'E3.title-129.http' $e2.Status 400
Check 'E4.title-129.message' ([string]$e2.Data.message) $MsgBadTitle
Check 'E5.no-insert-after-400.total' $mineAfterBad.Data.data.total $mineTotalBefore
Check 'E6.pageNum-101.http' $e3.Status 400
Check 'E7.pageNum-101.message' ([string]$e3.Data.message) $MsgPageTooDeep
Check 'E8.pageNum-0.http' $e4.Status 400
Check 'E9.pageSize-101.http' $e5.Status 400
Check 'E10.mine-status-trash.http' $e6.Status 400

# ============================ F. idempotent favorite ======================
Write-Host ''
Write-Host 'F. favorite / unfavorite idempotency'
$fav1 = Api -Method POST -Path ('/api/documents/' + $ownPub.id + '/favorite') -Token $staffToken
$fav2 = Api -Method POST -Path ('/api/documents/' + $ownPub.id + '/favorite') -Token $staffToken
Check 'F1.favorite.first.http' $fav1.Status 200
Check 'F2.favorite.repeat.http' $fav2.Status 200
Check-True 'F3.favorite.repeat.favorited-true' ($fav2.Data.data.favorited -eq $true) ('favorited=' + $fav2.Data.data.favorited)
Check 'F4.favorite.repeat.count-stable' ([string]$fav2.Data.data.favoriteCount) ([string]$fav1.Data.data.favoriteCount)
$un1 = Api -Method DELETE -Path ('/api/documents/' + $ownPub.id + '/favorite') -Token $staffToken
$un2 = Api -Method DELETE -Path ('/api/documents/' + $ownPub.id + '/favorite') -Token $staffToken
Check 'F5.unfavorite.first.http' $un1.Status 200
Check 'F6.unfavorite.repeat.http' $un2.Status 200
Check 'F7.unfavorite.repeat.count-stable' ([string]$un2.Data.data.favoriteCount) ([string]$un1.Data.data.favoriteCount)
# put the row back only if the fixture was favorited before this run
if ($ownPub.favoriteCount -is [int] -and $ownPub.favoriteCount -gt 0) {
    Api -Method POST -Path ('/api/documents/' + $ownPub.id + '/favorite') -Token $staffToken | Out-Null
}

# ============================ G. AC-08.3 permission tree cycle ============
Write-Host ''
Write-Host 'G. AC-08.3 moving a permission node under its own descendant'
$tree = (Api -Method GET -Path '/api/permissions/tree' -Token $adminToken).Data.data
$root = @($tree | Where-Object { $_.type -eq 'DIR' -and @($_.children).Count -gt 0 })[0]
$child = @($root.children)[0]
$g1 = Api -Method PUT -Path ('/api/permissions/' + $root.id) -Token $adminToken -Body (@{
    name = [string]$root.name; type = 'DIR'; parentId = [string]$child.id
    path = [string]$root.path; icon = $null; sortOrder = 0
})
$treeAfter = (Api -Method GET -Path '/api/permissions/tree' -Token $adminToken).Data.data
$rootAfter = @($treeAfter | Where-Object { [string]$_.id -eq [string]$root.id })[0]
Check 'G1.cycle.http' $g1.Status 400
Check 'G2.cycle.code' $g1.Data.code 400
Check 'G3.cycle.message' ([string]$g1.Data.message) $MsgCycle
Check 'G4.cycle.node-unchanged' ([string]$rootAfter.parentId) ([string]$root.parentId)
Check 'G5.cycle.children-unchanged' (@($rootAfter.children).Count) (@($root.children).Count)

# ============================ H. 404 ======================================
Write-Host ''
Write-Host 'H. 404 for a missing resource'
$h1 = Api -Method GET -Path '/api/documents/999999999' -Token $staffToken
Check 'H1.missing-document.http' $h1.Status 404
Check 'H2.missing-document.code' $h1.Data.code 404

Write-Host ''
Invoke-DbReload 'after run'
Write-Host '============================================================='
Write-Host (' RESULT: PASS=' + $script:Pass + ' FAIL=' + $script:Fail)
if ($script:Fail -gt 0) { Write-Host (' failed: ' + ($script:Failures -join ', ')) }
Write-Host '============================================================='
exit $script:Fail
