# =============================================================================
#  CampusSwap M3 HTTP acceptance check (T3.10)
#  - pure ASCII source (PowerShell 5.1 reads BOM-less UTF-8 as ANSI)
#  - Chinese payloads/messages are built from code points via Cn()
#  - requires the dev server running: mvnw spring-boot:run  (port 10087)
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File verify-m3-http.ps1
#    powershell -NoProfile -ExecutionPolicy Bypass -File verify-m3-http.ps1 -BaseUrl http://localhost:10087
#
#  Exit code = number of failed checks (0 = all pass)
# =============================================================================
param(
    [string]$BaseUrl = 'http://localhost:10087',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'Admin@123',
    [string]$StaffUser = 'staff',
    [string]$StaffPass = 'Staff@123'
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()
$script:Tmp = Join-Path $env:TEMP 'campusswap-m3-http'
if (-not (Test-Path $script:Tmp)) { New-Item -ItemType Directory -Path $script:Tmp -Force | Out-Null }
$script:Seq = 0

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Cn {
    param([string]$Codes)
    return (-join ($Codes.Split(',') | ForEach-Object { [char][int]('0x' + $_.Trim()) }))
}

function Write-Json {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = $null,
        [switch]$Raw
    )
    $script:Seq++
    $bodyFile = Join-Path $script:Tmp ('body-' + $script:Seq + '.json')
    $respFile = Join-Path $script:Tmp ('resp-' + $script:Seq + '.json')
    $curlArgs = @('-s', '-o', $respFile, '-w', '%{http_code}', '-X', $Method)
    if ($Token) { $curlArgs += @('-H', ('Authorization: Bearer ' + $Token)) }
    if ($Body -ne $null) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
        Write-Json -Path $bodyFile -Text $json
        $curlArgs += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $bodyFile))
    }
    $status = (& curl.exe @curlArgs ($BaseUrl + $Path)) -join ''
    $text = ''
    if (Test-Path $respFile) { $text = [System.IO.File]::ReadAllText($respFile, [System.Text.Encoding]::UTF8) }
    $data = $null
    if ($text -and -not $Raw) {
        try { $data = $text | ConvertFrom-Json } catch { $data = $null }
    }
    return [pscustomobject]@{ Status = [int]$status; Body = $text; Data = $data }
}

function Check {
    param([string]$Label, $Actual, $Expected)
    $ok = ($Actual -eq $Expected)
    if ($ok) {
        $script:Pass++
        Write-Host ('  [PASS] ' + $Label + ' = ' + $Actual)
    } else {
        $script:Fail++
        $script:Failures += $Label
        Write-Host ('  [FAIL] ' + $Label + ' : expected [' + $Expected + '] actual [' + $Actual + ']')
    }
}

function Check-True {
    param([string]$Label, $Condition, [string]$Detail = '')
    if ($Condition) {
        $script:Pass++
        Write-Host ('  [PASS] ' + $Label + ' ' + $Detail)
    } else {
        $script:Fail++
        $script:Failures += $Label
        Write-Host ('  [FAIL] ' + $Label + ' ' + $Detail)
    }
}

Write-Host '============================================================='
Write-Host ' CampusSwap M3 HTTP acceptance (T3.10)'
Write-Host (' BaseUrl = ' + $BaseUrl)
Write-Host '============================================================='

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[1] Auth: login / me / logout'
# -----------------------------------------------------------------------------
$login = Api -Method POST -Path '/api/auth/login' -Body @{ username = $AdminUser; password = $AdminPass }
Check 'login.http' $login.Status 200
Check 'login.code' $login.Data.code 200
$adminToken = $login.Data.data.token
Check-True 'login.token.32chars' ($adminToken.Length -eq 32) ('len=' + $adminToken.Length)
Check 'login.userInfo.id' $login.Data.data.userInfo.id '1'
Check 'login.roles[0]' $login.Data.data.userInfo.roles[0] 'SYS_ADMIN'
Check 'login.permission.count' ($login.Data.data.permissions.Count) 39
Check-True 'login.no.passwordHash' (($login.Body -like '*passwordHash*') -eq $false) ''

$me = Api -Method GET -Path '/api/auth/me' -Token $adminToken
Check 'me.http' $me.Status 200
Check 'me.username' $me.Data.data.username 'admin'

$noToken = Api -Method GET -Path '/api/users'
Check 'no-token.http' $noToken.Status 401
Check 'no-token.code' $noToken.Data.code 401
Check-True 'no-token.has-chinese-message' ($noToken.Data.message -ne $null -and $noToken.Data.message.Length -gt 0) ('msg=' + $noToken.Data.message)

$badPass = Api -Method POST -Path '/api/auth/login' -Body @{ username = $AdminUser; password = 'wrong-password' }
Check 'wrong-password.http' $badPass.Status 401
$enumMsg = Cn '7528,6237,540D,6216,5BC6,7801,9519,8BEF'
Check 'wrong-password.same-message-as-unknown-user' $badPass.Data.message $enumMsg
$unknown = Api -Method POST -Path '/api/auth/login' -Body @{ username = 'no_such_user_x'; password = 'whatever123' }
Check 'unknown-user.http' $unknown.Status 401
Check 'unknown-user.same-message' $unknown.Data.message $enumMsg

$shortPass = Api -Method POST -Path '/api/auth/login' -Body @{ username = 'admin'; password = '123' }
Check 'short-password.http' $shortPass.Status 400
$shortMsg = Cn '5BC6,7801,4E0D,80FD,4E3A,7A7A,FF0C,4E14,957F,5EA6,9700,5728,36,5230,36,34,4E4B,95F4'
Check 'short-password.message' $shortPass.Data.message $shortMsg

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[2] Permission tree (39 nodes, 1 SQL budget)'
# -----------------------------------------------------------------------------
$script:flatPerm = New-Object System.Collections.ArrayList
function Flatten-Perm {
    param($Nodes, [int]$Depth)
    foreach ($n in $Nodes) {
        [void]$script:flatPerm.Add([pscustomobject]@{ id = $n.id; code = $n.code; depth = $Depth })
        if ($n.children -and $n.children.Count -gt 0) { Flatten-Perm $n.children ($Depth + 1) }
    }
}

$tree = Api -Method GET -Path '/api/permissions/tree' -Token $adminToken
Check 'tree.http' $tree.Status 200
Flatten-Perm $tree.Data.data 0
# idempotency: remove leftovers of previous/interrupted runs (deepest first)
$leftovers = @($script:flatPerm | Where-Object { $_.code -like 'm3:*' -or $_.code -like 'test:*' } | Sort-Object depth -Descending)
if ($leftovers.Count -gt 0) {
    Write-Host ('  [INFO] removing ' + $leftovers.Count + ' leftover test permission node(s)')
    foreach ($l in $leftovers) { Api -Method DELETE -Path ('/api/permissions/' + $l.id) -Token $adminToken | Out-Null }
    $tree = Api -Method GET -Path '/api/permissions/tree' -Token $adminToken
    $script:flatPerm.Clear()
    Flatten-Perm $tree.Data.data 0
}
Check 'tree.node.count' ($script:flatPerm.Count) 39
Check 'tree.root.count' ($tree.Data.data.Count) 2

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[3] User CRUD + guards'
# -----------------------------------------------------------------------------
$users = Api -Method GET -Path '/api/users?pageNum=1&pageSize=10' -Token $adminToken
Check 'users.http' $users.Status 200
Check-True 'users.has-list' ($users.Data.data.list.Count -ge 3) ('count=' + $users.Data.data.list.Count)
Check-True 'users.no.passwordHash' (($users.Body -like '*passwordHash*') -eq $false) ''
Check-True 'users.deptName.filled' ($users.Data.data.list[0].deptName -ne $null) ('deptName=' + $users.Data.data.list[0].deptName)

Check 'users.page-num-deep.http' (Api -Method GET -Path '/api/users?pageNum=101' -Token $adminToken).Status 400
Check 'users.page-size-over.http' (Api -Method GET -Path '/api/users?pageSize=101' -Token $adminToken).Status 400
Check 'users.page-size-zero.http' (Api -Method GET -Path '/api/users?pageSize=0' -Token $adminToken).Status 400
Check 'users.dept-id-invalid.http' (Api -Method GET -Path '/api/users?deptId=abc' -Token $adminToken).Status 400
Check 'users.not-found.http' (Api -Method GET -Path '/api/users/999999' -Token $adminToken).Status 404

$newUser = 'u_m3test'
# idempotency: reuse the test account if a previous run left it behind
$existing = Api -Method GET -Path ('/api/users?pageNum=1&pageSize=10&keyword=' + $newUser) -Token $adminToken
$newUserId = $null
if ($existing.Data.data.total -gt 0) {
    $newUserId = $existing.Data.data.list[0].id
    Write-Host ('  [INFO] reuse existing test user id=' + $newUserId)
    Api -Method PUT -Path ('/api/users/' + $newUserId + '/status') -Token $adminToken -Body @{ status = 'ACTIVE' } | Out-Null
    Api -Method PUT -Path ('/api/users/' + $newUserId + '/password') -Token $adminToken -Body @{ newPassword = 'M3Test@123' } | Out-Null
}

$created = Api -Method POST -Path '/api/users' -Token $adminToken -Body @{
    username = $newUser; realName = (Cn '6D4B,8BD5,5458,5DE5'); deptId = '2'; roles = @('STAFF')
    email = 'm3test@example.com'; phone = '13900000001'; password = 'M3Test@123'
}
if ($newUserId) {
    Check 'user.create.duplicate-on-rerun.http' $created.Status 409
} else {
    Check 'user.create.http' $created.Status 200
    Check 'user.create.roles[0]' $created.Data.data.roles[0] 'STAFF'
    Check 'user.create.status' $created.Data.data.status 'ACTIVE'
    $newUserId = $created.Data.data.id
    Check-True 'user.create.id-is-string' ($newUserId -is [string]) ('id=' + $newUserId + ' type=' + $newUserId.GetType().Name)
}

$dup = Api -Method POST -Path '/api/users' -Token $adminToken -Body @{ username = $newUser; realName = 'dup'; deptId = '2'; password = 'M3Test@123' }
Check 'user.create.duplicate.http' $dup.Status 409
Check 'user.create.duplicate.code' $dup.Data.code 409

$badDept = Api -Method POST -Path '/api/users' -Token $adminToken -Body @{ username = 'u_baddept'; realName = 'x'; deptId = '999999'; password = 'M3Test@123' }
Check 'user.create.bad-dept.http' $badDept.Status 400
$badRole = Api -Method POST -Path '/api/users' -Token $adminToken -Body @{ username = 'u_badrole'; realName = 'x'; deptId = '2'; roles = @('NO_SUCH_ROLE'); password = 'M3Test@123' }
Check 'user.create.bad-role.http' $badRole.Status 400
$badPhone = Api -Method POST -Path '/api/users' -Token $adminToken -Body @{ username = 'u_badphone'; realName = 'x'; deptId = '2'; phone = '12345'; password = 'M3Test@123' }
Check 'user.create.bad-phone.http' $badPhone.Status 400

$grant = Api -Method PUT -Path ('/api/users/' + $newUserId) -Token $adminToken -Body @{
    realName = (Cn '6D4B,8BD5,5458,5DE5'); deptId = '2'; roles = @('DOC_ADMIN')
}
Check 'user.update.http' $grant.Status 200
Check 'user.update.roles[0]' $grant.Data.data.roles[0] 'DOC_ADMIN'

$detail = Api -Method GET -Path ('/api/users/' + $newUserId) -Token $adminToken
Check 'user.detail.http' $detail.Status 200
Check 'user.detail.roles[0]' $detail.Data.data.roles[0] 'DOC_ADMIN'

Check 'user.reset.weak.http' (Api -Method PUT -Path ('/api/users/' + $newUserId + '/password') -Token $adminToken -Body @{ newPassword = 'abc' }).Status 400
Check 'user.reset.http' (Api -Method PUT -Path ('/api/users/' + $newUserId + '/password') -Token $adminToken -Body @{ newPassword = 'NewM3@12345' }).Status 200

$userLogin = Api -Method POST -Path '/api/auth/login' -Body @{ username = $newUser; password = 'NewM3@12345' }
Check 'user.login.new-password.http' $userLogin.Status 200
$userToken = $userLogin.Data.data.token
Check 'user.login.old-password.http' (Api -Method POST -Path '/api/auth/login' -Body @{ username = $newUser; password = 'M3Test@123' }).Status 401

$self = Api -Method PUT -Path '/api/users/1/status' -Token $adminToken -Body @{ status = 'LOCKED' }
Check 'user.status.self.http' $self.Status 400
$selfMsg = Cn '4E0D,80FD,4FEE,6539,81EA,5DF1,7684,8D26,53F7,72B6,6001'
Check 'user.status.self.message' $self.Data.message $selfMsg

Check 'user.status.disable.http' (Api -Method PUT -Path ('/api/users/' + $newUserId + '/status') -Token $adminToken -Body @{ status = 'DISABLED' }).Status 200
Check 'user.status.kicked-token.http' (Api -Method GET -Path '/api/auth/me' -Token $userToken).Status 401
$disabledLogin = Api -Method POST -Path '/api/auth/login' -Body @{ username = $newUser; password = 'NewM3@12345' }
Check 'user.status.disabled-login.http' $disabledLogin.Status 403
Check 'user.status.disabled-login.code' $disabledLogin.Data.code 403
Check 'user.status.enable.http' (Api -Method PUT -Path ('/api/users/' + $newUserId + '/status') -Token $adminToken -Body @{ status = 'ACTIVE' }).Status 200

$reLogin = Api -Method POST -Path '/api/auth/login' -Body @{ username = $newUser; password = 'NewM3@12345' }
$reToken = $reLogin.Data.data.token
Check 'logout.first.http' (Api -Method POST -Path '/api/auth/logout' -Token $reToken).Status 200
Check 'logout.second.http' (Api -Method POST -Path '/api/auth/logout' -Token $reToken).Status 200
Check 'logout.token-dead.http' (Api -Method GET -Path '/api/auth/me' -Token $reToken).Status 401
Check 'logout.no-token.http' (Api -Method POST -Path '/api/auth/logout').Status 401

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[4] Guard rails: 403 for missing permission point'
# -----------------------------------------------------------------------------
$staff = Api -Method POST -Path '/api/auth/login' -Body @{ username = $StaffUser; password = $StaffPass }
Check 'staff.login.http' $staff.Status 200
$staffToken = $staff.Data.data.token
Check 'staff.permission.count' ($staff.Data.data.permissions.Count) 11
$forbidden = Api -Method GET -Path '/api/users?pageSize=1' -Token $staffToken
Check 'staff.forbidden.http' $forbidden.Status 403
$forbiddenMsg = Cn '65E0,6743,9650,6267,884C,8BE5,64CD,4F5C'
Check 'staff.forbidden.message' $forbidden.Data.message $forbiddenMsg
Check 'staff.me.http' (Api -Method GET -Path '/api/auth/me' -Token $staffToken).Status 200

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[5] Role CRUD + grant + builtin guards'
# -----------------------------------------------------------------------------
$roleCode = 'M3_TMP_ROLE'
$role = Api -Method POST -Path '/api/roles' -Token $adminToken -Body @{ name = (Cn '4E34,65F6,89D2,8272'); code = $roleCode; description = 'temp role for m3 check' }
Check 'role.create.http' $role.Status 200
Check 'role.create.isBuiltin' $role.Data.data.isBuiltin 0
$roleId = $role.Data.data.id
Check 'role.create.duplicate.http' (Api -Method POST -Path '/api/roles' -Token $adminToken -Body @{ name = 'dup'; code = $roleCode }).Status 409
Check 'role.update.code-immutable.http' (Api -Method PUT -Path ('/api/roles/' + $roleId) -Token $adminToken -Body @{ name = 'rename'; code = 'M3_TMP_ROLE2' }).Status 400
Check 'role.update.http' (Api -Method PUT -Path ('/api/roles/' + $roleId) -Token $adminToken -Body @{ name = 'renamed'; code = $roleCode }).Status 200
Check 'role.builtin.rename.http' (Api -Method PUT -Path '/api/roles/1' -Token $adminToken -Body @{ name = 'cannot'; code = 'STAFF' }).Status 409
Check 'role.builtin.delete.http' (Api -Method DELETE -Path '/api/roles/3' -Token $adminToken).Status 409
Check 'role.grant.http' (Api -Method PUT -Path ('/api/roles/' + $roleId + '/permissions') -Token $adminToken -Body @{ permissionIds = @('1', '10') }).Status 200
$rp = Api -Method GET -Path ('/api/roles/' + $roleId + '/permissions') -Token $adminToken
Check 'role.permissions.http' $rp.Status 200
Check 'role.permissions.count' ($rp.Data.data.permissionIds.Count) 2
Check 'role.permissions.roleId' $rp.Data.data.roleId $roleId
Check 'role.grant.bad-permission.http' (Api -Method PUT -Path ('/api/roles/' + $roleId + '/permissions') -Token $adminToken -Body @{ permissionIds = @('999999') }).Status 400
Check 'role.grant.empty.http' (Api -Method PUT -Path ('/api/roles/' + $roleId + '/permissions') -Token $adminToken -Body @{ permissionIds = @() }).Status 200
Check 'role.delete.http' (Api -Method DELETE -Path ('/api/roles/' + $roleId) -Token $adminToken).Status 200
Check 'role.delete.again.http' (Api -Method DELETE -Path ('/api/roles/' + $roleId) -Token $adminToken).Status 404
$roles = Api -Method GET -Path '/api/roles?pageNum=1&pageSize=10' -Token $adminToken
Check 'role.list.http' $roles.Status 200
Check 'role.list.builtin-first' $roles.Data.data.list[0].code 'STAFF'

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[6] Permission CRUD: hierarchy / ancestors / cycle / delete guards'
# -----------------------------------------------------------------------------
Check 'perm.dir-under-dir.http' (Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'bad'; code = 'm3:dir'; type = 'DIR'; parentId = '1' }).Status 400
Check 'perm.button-under-dir.http' (Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'bad'; code = 'm3:btn'; type = 'BUTTON'; parentId = '1' }).Status 400
Check 'perm.menu-without-path.http' (Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'bad'; code = 'm3:menu'; type = 'MENU'; parentId = '1' }).Status 400
Check 'perm.bad-type.http' (Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'bad'; code = 'm3:menu'; type = 'WRONG'; parentId = '1'; path = '/x' }).Status 400

$menu = Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'm3 menu'; code = 'm3:menu'; type = 'MENU'; parentId = '1'; path = '/m3'; sortOrder = 99 }
Check 'perm.menu.create.http' $menu.Status 200
$menuId = $menu.Data.data.id
Check 'perm.menu.ancestors' $menu.Data.data.ancestors '0,1'
Check 'perm.menu.parentId' $menu.Data.data.parentId '1'

$btn = Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'm3 button'; code = 'm3:btn'; type = 'BUTTON'; parentId = $menuId }
Check 'perm.button.create.http' $btn.Status 200
$btnId = $btn.Data.data.id
Check 'perm.button.ancestors' $btn.Data.data.ancestors ('0,1,' + $menuId)
Check 'perm.button.path-null' ([string]$btn.Data.data.path) ''

Check 'perm.code.duplicate.http' (Api -Method POST -Path '/api/permissions' -Token $adminToken -Body @{ name = 'dup'; code = 'm3:btn'; type = 'BUTTON'; parentId = '11' }).Status 409
Check 'perm.delete-with-children.http' (Api -Method DELETE -Path ('/api/permissions/' + $menuId) -Token $adminToken).Status 409
Check 'perm.move-node1-under-own-child.http' (Api -Method PUT -Path '/api/permissions/1' -Token $adminToken -Body @{ name = 'doc center'; type = 'DIR'; parentId = $menuId; path = '/docs' }).Status 400
Check 'perm.move-menu-under-itself.http' (Api -Method PUT -Path ('/api/permissions/' + $menuId) -Token $adminToken -Body @{ name = 'm3 menu'; type = 'MENU'; parentId = $menuId; path = '/m3' }).Status 400

$moved = Api -Method PUT -Path ('/api/permissions/' + $btnId) -Token $adminToken -Body @{ name = 'm3 button'; type = 'BUTTON'; parentId = '11' }
Check 'perm.move-button.http' $moved.Status 200
Check 'perm.move-button.ancestors-rewritten' $moved.Data.data.ancestors '0,1,11'

Check 'perm.delete.button.http' (Api -Method DELETE -Path ('/api/permissions/' + $btnId) -Token $adminToken).Status 200
Check 'perm.delete.menu.http' (Api -Method DELETE -Path ('/api/permissions/' + $menuId) -Token $adminToken).Status 200
Check 'perm.delete.referenced.http' (Api -Method DELETE -Path '/api/permissions/10' -Token $adminToken).Status 409

$tree2 = Api -Method GET -Path '/api/permissions/tree' -Token $adminToken
$script:flatPerm2 = New-Object System.Collections.ArrayList
function Flatten-Perm2 {
    param($Nodes, [int]$Depth)
    foreach ($n in $Nodes) {
        [void]$script:flatPerm2.Add([pscustomobject]@{ id = $n.id; code = $n.code; depth = $Depth })
        if ($n.children -and $n.children.Count -gt 0) { Flatten-Perm2 $n.children ($Depth + 1) }
    }
}
Flatten-Perm2 $tree2.Data.data 0
Check 'perm.tree.restored' ($script:flatPerm2.Count) 39

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[7] Dept tree + role binding + delete guards'
# -----------------------------------------------------------------------------
$depts = Api -Method GET -Path '/api/depts/tree' -Token $adminToken
Check 'dept.tree.http' $depts.Status 200
# seed data: 2 roots (dept 1 tech / dept 2 product) + 1 child (dept 3 under dept 1)
$script:flatDept = New-Object System.Collections.ArrayList
function Flatten-Dept {
    param($Nodes, [int]$Depth)
    foreach ($n in $Nodes) {
        [void]$script:flatDept.Add([pscustomobject]@{ id = $n.id; name = $n.name; depth = $Depth })
        if ($n.children -and $n.children.Count -gt 0) { Flatten-Dept $n.children ($Depth + 1) }
    }
}
Flatten-Dept $depts.Data.data 0
$deptLeftovers = @($script:flatDept | Where-Object { $_.name -eq 'm3 dept' } | Sort-Object depth -Descending)
if ($deptLeftovers.Count -gt 0) {
    Write-Host ('  [INFO] removing ' + $deptLeftovers.Count + ' leftover test dept(s)')
    foreach ($d in $deptLeftovers) { Api -Method DELETE -Path ('/api/depts/' + $d.id) -Token $adminToken | Out-Null }
    $depts = Api -Method GET -Path '/api/depts/tree' -Token $adminToken
    $script:flatDept.Clear()
    Flatten-Dept $depts.Data.data 0
}
Check 'dept.tree.node.count' ($script:flatDept.Count) 3
Check 'dept.tree.root.count' ($depts.Data.data.Count) 2
Check 'dept.tree.root.parentId' $depts.Data.data[0].parentId '0'

$dept = Api -Method POST -Path '/api/depts' -Token $adminToken -Body @{ name = 'm3 dept'; parentId = '1'; sortOrder = 99 }
Check 'dept.create.http' $dept.Status 200
$deptId = $dept.Data.data.id
Check 'dept.create.ancestors' $dept.Data.data.ancestors '0,1'
Check 'dept.create.duplicate.http' (Api -Method POST -Path '/api/depts' -Token $adminToken -Body @{ name = 'm3 dept'; parentId = '1' }).Status 400
Check 'dept.move-under-itself.http' (Api -Method PUT -Path ('/api/depts/' + $deptId) -Token $adminToken -Body @{ name = 'm3 dept'; parentId = $deptId }).Status 400
Check 'dept.bind-roles.http' (Api -Method PUT -Path ('/api/depts/' + $deptId + '/roles') -Token $adminToken -Body @{ roleIds = @('1') }).Status 200
$dr = Api -Method GET -Path ('/api/depts/' + $deptId + '/roles') -Token $adminToken
Check 'dept.roles.http' $dr.Status 200
Check 'dept.roles.count' ($dr.Data.data.roleIds.Count) 1
Check 'dept.roles[0]' $dr.Data.data.roleIds[0] '1'
Check 'dept.bind-bad-role.http' (Api -Method PUT -Path ('/api/depts/' + $deptId + '/roles') -Token $adminToken -Body @{ roleIds = @('999999') }).Status 400
Check 'dept.delete-with-children.http' (Api -Method DELETE -Path '/api/depts/1' -Token $adminToken).Status 409
Check 'dept.delete.http' (Api -Method DELETE -Path ('/api/depts/' + $deptId) -Token $adminToken).Status 200
Check 'dept.delete.again.http' (Api -Method DELETE -Path ('/api/depts/' + $deptId) -Token $adminToken).Status 404

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[8] Authorization takes effect immediately (US-08 AC-08.1)'
# -----------------------------------------------------------------------------
$sysUserPermId = '20'
$before = Api -Method GET -Path '/api/users?pageSize=1' -Token $staffToken
Check 'grant-effect.before.http' $before.Status 403
$orig = Api -Method GET -Path '/api/roles/1/permissions' -Token $adminToken
$origIds = @($orig.Data.data.permissionIds)
Check 'grant-effect.orig-count' $origIds.Count 11
$extended = @($origIds) + @($sysUserPermId)
Check 'grant-effect.grant.http' (Api -Method PUT -Path '/api/roles/1/permissions' -Token $adminToken -Body @{ permissionIds = $extended }).Status 200
$staffMe = Api -Method GET -Path '/api/auth/me' -Token $staffToken
Check 'grant-effect.after.permission-count' ($staffMe.Data.data.permissions.Count) 12
Check 'grant-effect.after.http' (Api -Method GET -Path '/api/users?pageSize=1' -Token $staffToken).Status 200
Check 'grant-effect.revert.http' (Api -Method PUT -Path '/api/roles/1/permissions' -Token $adminToken -Body @{ permissionIds = $origIds }).Status 200
$staffMe2 = Api -Method GET -Path '/api/auth/me' -Token $staffToken
Check 'grant-effect.reverted.permission-count' ($staffMe2.Data.data.permissions.Count) 11
Check 'grant-effect.reverted.http' (Api -Method GET -Path '/api/users?pageSize=1' -Token $staffToken).Status 403

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[9] Cleanup test data'
# -----------------------------------------------------------------------------
$cleanupUser = Api -Method PUT -Path ('/api/users/' + $newUserId + '/status') -Token $adminToken -Body @{ status = 'DISABLED' }
Check 'cleanup.disable-test-user.http' $cleanupUser.Status 200
Write-Host ('  [INFO] test user id=' + $newUserId + ' left in DISABLED state (no delete API by design)')

Write-Host ''
Write-Host '============================================================='
Write-Host (' RESULT: PASS=' + $script:Pass + '  FAIL=' + $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host ' Failed checks:'
    foreach ($f in $script:Failures) { Write-Host ('   - ' + $f) }
}
Write-Host '============================================================='
exit $script:Fail
