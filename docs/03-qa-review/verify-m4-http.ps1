# =============================================================================
#  CampusSwap M4 HTTP acceptance check (document domain, 30 endpoints)
#  - pure ASCII source (PowerShell 5.1 reads BOM-less UTF-8 as ANSI)
#  - Chinese expectations are built from code points via Cn()
#  - requires the dev server: mvnw spring-boot:run   (port 10087)
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File verify-m4-http.ps1
#    powershell -NoProfile -ExecutionPolicy Bypass -File verify-m4-http.ps1 -AppLog "$env:TEMP\campusswap-app.log"
#
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
    [string]$AppLog = ''
)

$ErrorActionPreference = 'Continue'
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()
$script:Tmp = Join-Path $env:TEMP 'campusswap-m4-http'
if (-not (Test-Path $script:Tmp)) { New-Item -ItemType Directory -Path $script:Tmp -Force | Out-Null }
$script:Seq = 0
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Cn {
    param([string]$Codes)
    return (-join ($Codes.Split(',') | ForEach-Object { [char][int]('0x' + $_.Trim()) }))
}

function Api {
    param([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [switch]$NoBody)
    $script:Seq++
    $bodyFile = Join-Path $script:Tmp ('b-' + $script:Seq + '.json')
    $respFile = Join-Path $script:Tmp ('r-' + $script:Seq + '.json')
    $curlArgs = @('-s', '-o', $respFile, '-w', '%{http_code}', '-X', $Method)
    if ($Token) { $curlArgs += @('-H', ('Authorization: Bearer ' + $Token)) }
    if (-not $NoBody -and $Body -ne $null) {
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

function Count-Sql {
    param([string]$Label, [string]$Path, [string]$Token, $Expected)
    if (-not $AppLog -or -not (Test-Path -LiteralPath $AppLog)) { return }
    $before = @(Get-Content -LiteralPath $AppLog).Count
    & curl.exe -s -o NUL -H ('Authorization: Bearer ' + $Token) ($BaseUrl + $Path) | Out-Null
    Start-Sleep -Milliseconds 600
    $lines = Get-Content -LiteralPath $AppLog
    $new = @($lines[$before..($lines.Count - 1)])
    $sql = @($new | Where-Object { $_ -like 'Hibernate:*' })
    Check ('sql.' + $Label) $sql.Count $Expected
}

Write-Host '============================================================='
Write-Host ' CampusSwap M4 HTTP acceptance (document domain)'
Write-Host (' BaseUrl = ' + $BaseUrl)
Write-Host (' AppLog  = ' + $(if ($AppLog) { $AppLog } else { '(sql checks skipped)' }))
Write-Host '============================================================='

# --- tokens -----------------------------------------------------------------
$adminLogin = Api -Method POST -Path '/api/auth/login' -Body @{ username = $AdminUser; password = $AdminPass }
$admin = $adminLogin.Data.data.token
$docAdmin = (Api -Method POST -Path '/api/auth/login' -Body @{ username = $DocAdminUser; password = $DocAdminPass }).Data.data.token
$staff = (Api -Method POST -Path '/api/auth/login' -Body @{ username = $StaffUser; password = $StaffPass }).Data.data.token
Check 'setup.tokens' (($admin.Length -eq 32) -and ($docAdmin.Length -eq 32) -and ($staff.Length -eq 32)) $true

$stamp = Get-Date -Format 'HHmmss'
$titleA = 'M4-A-' + $stamp
$titleB = 'M4-B-' + $stamp

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[US-02] create draft'
# -----------------------------------------------------------------------------
$created = Api -Method POST -Path '/api/documents' -Token $admin -Body @{
    title = $titleA; summary = 'acceptance summary'; contentMd = "# M4`nbody line"
    categoryId = '1'; tagIds = @('1', '2'); priceCents = 0
}
Check 'US02.create.http' $created.Status 200
$docA = $created.Data.data.id
Check 'US02.create.status' $created.Data.data.status 'DRAFT'
Check 'US02.create.versionNum' $created.Data.data.versionNum 1
Check 'US02.create.authorIsCreator' $created.Data.data.authorId $created.Data.data.createdBy
Check 'US02.create.priceCents' $created.Data.data.priceCents 0
Check 'US02.create.tags.count' ($created.Data.data.tags.Count) 2
Check 'US02.create.categoryName.filled' ($created.Data.data.categoryName -ne $null) $true

Check 'US02.empty-title.http' (Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = ''; contentMd = 'x' }).Status 400
Check 'US02.title-129.http' (Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = ('x' * 129); contentMd = 'x' }).Status 400
$noInsert = Api -Method GET -Path ('/api/documents/mine?keyword=' + ('x' * 20) + '&pageSize=5') -Token $admin
Check 'US02.no-insert-after-400.total' $noInsert.Data.data.total 0
Check 'US02.bad-category.http' (Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = 'bad-cat'; categoryId = '999999' }).Status 400
Check 'US02.six-tags.http' (Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = 'bad-tags'; tagIds = @('1', '2', '3', '4', '5', '6') }).Status 400
Check 'US02.no-token.http' (Api -Method POST -Path '/api/documents' -Body @{ title = 'no-token' }).Status 401

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[US-03] publish'
# -----------------------------------------------------------------------------
$pub = Api -Method POST -Path ('/api/documents/' + $docA + '/publish') -Token $admin
Check 'US03.publish.http' $pub.Status 200
Check 'US03.publish.status' $pub.Data.data.status 'PUBLISHED'
Check 'US03.publish.versionNum' $pub.Data.data.versionNum 2
Check 'US03.republish.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/publish') -Token $admin).Status 409

$empty = Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = ('M4-empty-' + $stamp); contentMd = '' }
$emptyId = $empty.Data.data.id
Check 'US03.publish-empty-content.http' (Api -Method POST -Path ('/api/documents/' + $emptyId + '/publish') -Token $admin).Status 409
Check 'US03.publish-others-doc.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/publish') -Token $staff).Status 403

$versions = Api -Method GET -Path ('/api/documents/' + $docA + '/versions?pageSize=10') -Token $admin
Check 'US03.versions.http' $versions.Status 200
Check 'US03.versions.total' $versions.Data.data.total 2
Check 'US03.versions.newest-first' $versions.Data.data.list[0].changeType 'PUBLISH'

# publish a trashed document must be 409 (US-03 AC-03.3)
$trashPub = Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = ('M4-TRASHPUB-' + $stamp); contentMd = 'to be trashed' }
$trashPubId = $trashPub.Data.data.id
Api -Method DELETE -Path ('/api/documents/' + $trashPubId) -Token $admin | Out-Null
Check 'US03.publish-trashed.http' (Api -Method POST -Path ('/api/documents/' + $trashPubId + '/publish') -Token $admin).Status 409
Check 'US03.derive-trashed.http' (Api -Method POST -Path ('/api/documents/' + $trashPubId + '/derive') -Token $admin -Body @{}).Status 409
Check 'US03.cleanup-trashed.http' (Api -Method DELETE -Path ('/api/documents/' + $trashPubId + '/destroy') -Token $admin -Body @{ confirm = $true }).Status 200

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[US-04] search / category / tag'
# -----------------------------------------------------------------------------
$search = Api -Method GET -Path ('/api/documents?keyword=' + $titleA + '&pageSize=10') -Token $staff
Check 'US04.search.http' $search.Status 200
Check 'US04.search.total' $search.Data.data.total 1
Check-True 'US04.search.has-authorName' ($search.Data.data.list[0].authorName -ne $null) ''
Check-True 'US04.search.no-contentMd' (($search.Body -like '*contentMd*') -eq $false) ''

$none = Api -Method GET -Path '/api/documents?keyword=zzz-not-exist-zzz' -Token $staff
Check 'US04.search-no-hit.http' $none.Status 200
Check 'US04.search-no-hit.total' $none.Data.data.total 0

$draft = Api -Method POST -Path '/api/documents' -Token $admin -Body @{ title = ('M4-DRAFT-' + $stamp); contentMd = 'draft body' }
$draftId = $draft.Data.data.id
$draftSearch = Api -Method GET -Path ('/api/documents?keyword=M4-DRAFT-' + $stamp) -Token $staff
Check 'US04.search-excludes-others-draft.total' $draftSearch.Data.data.total 0
Check 'US04.detail-others-draft.http' (Api -Method GET -Path ('/api/documents/' + $draftId) -Token $staff).Status 403
Check 'US04.detail-others-draft-as-manage.http' (Api -Method GET -Path ('/api/documents/' + $draftId) -Token $docAdmin).Status 200

Check 'US04.status-draft.http' (Api -Method GET -Path '/api/documents?status=DRAFT' -Token $admin).Status 400
Check 'US04.sort-invalid.http' (Api -Method GET -Path '/api/documents?sort=wrong_sort' -Token $admin).Status 400
Check 'US04.category-format.http' (Api -Method GET -Path '/api/documents?categoryId=abc' -Token $admin).Status 400
Check 'US04.category-bad.http' (Api -Method GET -Path '/api/documents?categoryId=999999' -Token $admin).Status 400
$byCat = Api -Method GET -Path '/api/documents?categoryId=1&pageSize=100' -Token $admin
Check 'US04.category-descendants.http' $byCat.Status 200
Check-True 'US04.category-descendants.total' ($byCat.Data.data.total -ge 1) ('total=' + $byCat.Data.data.total)

$catTree = Api -Method GET -Path '/api/categories/tree' -Token $staff
Check 'US04.category-tree.http' $catTree.Status 200
Check-True 'US04.category-tree.roots' ($catTree.Data.data.Count -ge 1) ('roots=' + $catTree.Data.data.Count)

$tags = Api -Method GET -Path '/api/tags?pageSize=100' -Token $staff
Check 'US04.tags.http' $tags.Status 200
Check-True 'US04.tags.count' ($tags.Data.data.total -ge 5) ('total=' + $tags.Data.data.total)
Check-True 'US04.tags.hot-first' ($tags.Data.data.list[0].useCount -ge $tags.Data.data.list[1].useCount) ''

Check 'US04.mine.http' (Api -Method GET -Path '/api/documents/mine?pageSize=100' -Token $admin).Status 200
Check 'US04.mine-trash-status.http' (Api -Method GET -Path '/api/documents/mine?status=TRASH' -Token $admin).Status 400
Check 'US04.mine-bad-status.http' (Api -Method GET -Path '/api/documents/mine?status=WRONG' -Token $admin).Status 400

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[US-05] derive + favorite + view count'
# -----------------------------------------------------------------------------
$derived = Api -Method POST -Path ('/api/documents/' + $docA + '/derive') -Token $admin -Body @{}
Check 'US05.derive.http' $derived.Status 200
Check 'US05.derive.status' $derived.Data.data.status 'DRAFT'
Check 'US05.derive.derivedFromId' $derived.Data.data.derivedFromId $docA
Check-True 'US05.derive.title-copy' ($derived.Data.data.title -like ($titleA + '*')) ('title=' + $derived.Data.data.title)
Check 'US05.derive.versionNum' $derived.Data.data.versionNum 1
Check 'US05.derive.content-prefilled' $derived.Data.data.contentMd "# M4`nbody line"
Check 'US05.derive.source-version-unchanged' (Api -Method GET -Path ('/api/documents/' + $docA) -Token $admin).Data.data.versionNum 2
Check 'US05.derive-invisible-source.http' (Api -Method POST -Path ('/api/documents/' + $draftId + '/derive') -Token $staff -Body @{}).Status 403

$fav = Api -Method POST -Path ('/api/documents/' + $docA + '/favorite') -Token $staff
Check 'US05.favorite.http' $fav.Status 200
Check 'US05.favorite.favorited' $fav.Data.data.favorited $true
Check 'US05.favorite.idempotent-count' (Api -Method POST -Path ('/api/documents/' + $docA + '/favorite') -Token $staff).Data.data.favoriteCount 1
Check 'US05.unfavorite.http' (Api -Method DELETE -Path ('/api/documents/' + $docA + '/favorite') -Token $staff).Status 200
Check 'US05.unfavorite.idempotent.http' (Api -Method DELETE -Path ('/api/documents/' + $docA + '/favorite') -Token $staff).Status 200
Check 'US05.favorites.http' (Api -Method GET -Path '/api/favorites?pageSize=10' -Token $staff).Status 200

$view1 = Api -Method GET -Path ('/api/documents/' + $docA) -Token $staff
$view2 = Api -Method GET -Path ('/api/documents/' + $docA) -Token $staff
Check 'US05.view-count.dedup' $view2.Data.data.viewCount $view1.Data.data.viewCount

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[US-06] edit + version'
# -----------------------------------------------------------------------------
Check 'US06.id-mismatch.http' (Api -Method PUT -Path ('/api/documents/' + $docA) -Token $admin -Body @{ id = '999999'; title = 'x'; contentMd = 'y' }).Status 400
$edit = Api -Method PUT -Path ('/api/documents/' + $docA) -Token $admin -Body @{
    id = $docA; title = ($titleA + ' v2'); summary = 'edited'; contentMd = "# M4`nbody edited"
    categoryId = '1'; tagIds = @('1'); priceCents = 123
}
Check 'US06.edit.http' $edit.Status 200
Check 'US06.edit.versionNum' $edit.Data.data.versionNum 3
Check 'US06.edit.priceCents' $edit.Data.data.priceCents 123
Check 'US06.edit.tags.count' ($edit.Data.data.tags.Count) 1
$editVersions = Api -Method GET -Path ('/api/documents/' + $docA + '/versions?pageSize=10') -Token $admin
Check 'US06.version-record-added' $editVersions.Data.data.total 3
Check 'US06.version.newest-is-edit' $editVersions.Data.data.list[0].changeType 'EDIT'
Check 'US06.edit-others-doc.http' (Api -Method PUT -Path ('/api/documents/' + $docA) -Token $staff -Body @{ id = $docA; title = 'hacked'; contentMd = 'hacked' }).Status 403
Check 'US06.content-unchanged-after-403' (Api -Method GET -Path ('/api/documents/' + $docA) -Token $admin).Data.data.title ($titleA + ' v2')
Check 'US06.versions-others.http' (Api -Method GET -Path ('/api/documents/' + $docA + '/versions') -Token $staff).Status 403

$archived = Api -Method POST -Path ('/api/documents/' + $docA + '/archive') -Token $docAdmin -Body @{ remark = 'archive for read-only check' }
Check 'US06.archive.http' $archived.Status 200
Check 'US06.archive.status' $archived.Data.data.status 'ARCHIVED'
Check 'US06.edit-archived.http' (Api -Method PUT -Path ('/api/documents/' + $docA) -Token $admin -Body @{ id = $docA; title = 'cannot'; contentMd = 'cannot' }).Status 409
Check 'US06.republish.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/republish') -Token $docAdmin).Status 200
Check 'US06.republish.status' (Api -Method GET -Path ('/api/documents/' + $docA) -Token $admin).Data.data.status 'PUBLISHED'

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[US-07] review / reject / archive / trash state machine'
# -----------------------------------------------------------------------------
$reviewList = Api -Method GET -Path '/api/review/documents?pageSize=100' -Token $docAdmin
Check 'US07.review-list.http' $reviewList.Status 200
Check-True 'US07.review-list.total' ($reviewList.Data.data.total -ge 1) ('total=' + $reviewList.Data.data.total)
Check-True 'US07.review-list.canEdit-false' ($reviewList.Data.data.list[0].canEdit -eq $false) ''
Check 'US07.review-list.status-draft.http' (Api -Method GET -Path '/api/review/documents?status=DRAFT' -Token $docAdmin).Status 400
Check 'US07.review-list.staff.http' (Api -Method GET -Path '/api/review/documents' -Token $staff).Status 403

$audit = Api -Method POST -Path ('/api/documents/' + $docA + '/audit') -Token $docAdmin -Body @{ remark = 'content ok' }
Check 'US07.audit.http' $audit.Status 200
Check 'US07.audit.status-unchanged' $audit.Data.data.status 'PUBLISHED'
Check 'US07.audit.remark-blank.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/audit') -Token $docAdmin -Body @{ remark = '' }).Status 400
Check 'US07.audit.staff.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/audit') -Token $staff -Body @{ remark = 'x' }).Status 403

$reject = Api -Method POST -Path ('/api/documents/' + $docA + '/reject') -Token $docAdmin -Body @{ reason = (Cn '7B2C,4E09,7AE0,7F3A,5C11,9274,6743,8BF4,660E') }
Check 'US07.reject.http' $reject.Status 200
Check 'US07.reject.status' $reject.Data.data.status 'DRAFT'
Check-True 'US07.reject.reason-visible' ($reject.Data.data.rejectReason -ne $null) ('reason=' + $reject.Data.data.rejectReason)
Check 'US07.reject-reason-blank.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/reject') -Token $docAdmin -Body @{ reason = '' }).Status 400

Check 'US07.publish-after-reject.http' (Api -Method POST -Path ('/api/documents/' + $docA + '/publish') -Token $admin).Status 200
$auditVersions = Api -Method GET -Path ('/api/documents/' + $docA + '/versions?pageSize=20') -Token $admin
Check-True 'US07.audit-in-versions' ((@($auditVersions.Data.data.list | Where-Object { $_.changeType -eq 'AUDIT' })).Count -eq 1) ''
Check-True 'US07.audit-remark-in-version' ((@($auditVersions.Data.data.list | Where-Object { $_.changeType -eq 'AUDIT' -and $_.changeRemark -eq 'content ok' })).Count -eq 1) ''
Check-True 'US07.archive-remark-in-version' ((@($auditVersions.Data.data.list | Where-Object { $_.changeType -eq 'ARCHIVE' -and $_.changeRemark -eq 'archive for read-only check' })).Count -eq 1) ''
Check-True 'US07.reject-in-versions' ((@($auditVersions.Data.data.list | Where-Object { $_.changeType -eq 'REJECT' })).Count -eq 1) ''
Check 'US07.versions.operator-name' $auditVersions.Data.data.list[0].operatorName (Api -Method GET -Path '/api/auth/me' -Token $admin).Data.data.realName
# US-08 AC-08.2: non-admin cannot grant permissions
Check 'US08.staff-grant.http' (Api -Method PUT -Path '/api/roles/1/permissions' -Token $staff -Body @{ permissionIds = @('1') }).Status 403

# trash -> publish/derive blocked -> restore -> destroy
Check 'US07.delete.http' (Api -Method DELETE -Path ('/api/documents/' + $docA) -Token $admin).Status 200
Check 'US07.delete-again.http' (Api -Method DELETE -Path ('/api/documents/' + $docA) -Token $admin).Status 409
Check 'US07.detail-trashed-owner.http' (Api -Method GET -Path ('/api/documents/' + $docA) -Token $admin).Status 200
Check 'US07.trash-list.http' (Api -Method GET -Path '/api/documents/trash?pageSize=100' -Token $admin).Status 200
Check 'US07.destroy-not-in-trash.http' (Api -Method DELETE -Path ('/api/documents/' + $emptyId + '/destroy') -Token $admin -Body @{ confirm = $true }).Status 409
Check 'US07.restore-not-in-trash.http' (Api -Method POST -Path ('/api/documents/' + $emptyId + '/restore') -Token $admin).Status 409
Check 'US07.destroy-confirm-missing.http' (Api -Method DELETE -Path ('/api/documents/' + $docA + '/destroy') -Token $admin -Body @{ confirm = $false }).Status 400
$restored = Api -Method POST -Path ('/api/documents/' + $docA + '/restore') -Token $admin
Check 'US07.restore.http' $restored.Status 200
Check 'US07.restore.status' $restored.Data.data.status 'DRAFT'
Check 'US07.destroy.http' (Api -Method DELETE -Path ('/api/documents/' + $docA) -Token $admin).Status 200
Check 'US07.destroy.real.http' (Api -Method DELETE -Path ('/api/documents/' + $docA + '/destroy') -Token $admin -Body @{ confirm = $true }).Status 200
Check 'US07.detail-after-destroy.http' (Api -Method GET -Path ('/api/documents/' + $docA) -Token $admin).Status 404
Check 'US07.destroy-cascade.versions404' (Api -Method GET -Path ('/api/documents/' + $docA + '/versions') -Token $admin).Status 404

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[category / tag write paths]'
# -----------------------------------------------------------------------------
$cat = Api -Method POST -Path '/api/categories' -Token $docAdmin -Body @{ name = ('m4cat-' + $stamp); parentId = '0'; sortOrder = 90 }
Check 'cat.create.http' $cat.Status 200
$catId = $cat.Data.data.id
Check 'cat.create.ancestors' $cat.Data.data.ancestors '0'
Check 'cat.create.duplicate.http' (Api -Method POST -Path '/api/categories' -Token $docAdmin -Body @{ name = ('m4cat-' + $stamp); parentId = '0' }).Status 400
Check 'cat.move-under-itself.http' (Api -Method PUT -Path ('/api/categories/' + $catId) -Token $docAdmin -Body @{ name = ('m4cat-' + $stamp); parentId = $catId }).Status 400
Check 'cat.staff-create.http' (Api -Method POST -Path '/api/categories' -Token $staff -Body @{ name = 'nope'; parentId = '0' }).Status 403
$catChild = Api -Method POST -Path '/api/categories' -Token $docAdmin -Body @{ name = ('m4cat-child-' + $stamp); parentId = $catId }
Check 'cat.child.create.http' $catChild.Status 200
Check 'cat.child.ancestors' $catChild.Data.data.ancestors ('0,' + $catId)
$catGrand = Api -Method POST -Path '/api/categories' -Token $docAdmin -Body @{ name = ('m4cat-grand-' + $stamp); parentId = $catChild.Data.data.id }
Check 'cat.third-level.http' $catGrand.Status 200
Check 'cat.fourth-level.http' (Api -Method POST -Path '/api/categories' -Token $docAdmin -Body @{ name = ('m4cat-4th-' + $stamp); parentId = $catGrand.Data.data.id }).Status 400
Check 'cat.delete-with-child.http' (Api -Method DELETE -Path ('/api/categories/' + $catId) -Token $docAdmin).Status 409
Check 'cat.delete-grandchild.http' (Api -Method DELETE -Path ('/api/categories/' + $catGrand.Data.data.id) -Token $docAdmin).Status 200
Check 'cat.delete-child.http' (Api -Method DELETE -Path ('/api/categories/' + $catChild.Data.data.id) -Token $docAdmin).Status 200
Check 'cat.delete.http' (Api -Method DELETE -Path ('/api/categories/' + $catId) -Token $docAdmin).Status 200

$tag = Api -Method POST -Path '/api/tags' -Token $docAdmin -Body @{ name = ('m4tag' + $stamp) }
Check 'tag.create.http' $tag.Status 200
$tagId = $tag.Data.data.id
Check 'tag.create.useCount' $tag.Data.data.useCount 0
Check 'tag.create.duplicate.http' (Api -Method POST -Path '/api/tags' -Token $docAdmin -Body @{ name = ('m4tag' + $stamp) }).Status 409
Check 'tag.create-too-long.http' (Api -Method POST -Path '/api/tags' -Token $docAdmin -Body @{ name = 'x' * 17 }).Status 400
Check 'tag.update.http' (Api -Method PUT -Path ('/api/tags/' + $tagId) -Token $docAdmin -Body @{ name = ('m4tag2-' + $stamp) }).Status 200
Check 'tag.update.duplicate.http' (Api -Method PUT -Path ('/api/tags/' + $tagId) -Token $docAdmin -Body @{ name = 'SpringBoot' }).Status 409
Check 'tag.staff-create.http' (Api -Method POST -Path '/api/tags' -Token $staff -Body @{ name = 'nope' }).Status 403
Check 'tag.delete.http' (Api -Method DELETE -Path ('/api/templates/' + $tagId) -Token $docAdmin).Status 404
Check 'tag.delete.real.http' (Api -Method DELETE -Path ('/api/tags/' + $tagId) -Token $docAdmin).Status 200
Check 'tag.delete.again.http' (Api -Method DELETE -Path ('/api/tags/' + $tagId) -Token $docAdmin).Status 404
# soft delete must release the unique key: the same tag name can be created again (ARCHITECTURE rule 18)
Check 'tag.recreate-after-delete.http' (Api -Method POST -Path '/api/tags' -Token $docAdmin -Body @{ name = ('m4tag2-' + $stamp) }).Status 200

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[upload / stats]'
# -----------------------------------------------------------------------------
$png = Join-Path $script:Tmp 'tiny.png'
$pngBytes = [byte[]]@(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89,
    0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00,
    0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82)
[System.IO.File]::WriteAllBytes($png, $pngBytes)
$txt = Join-Path $script:Tmp 'notimage.txt'
[System.IO.File]::WriteAllText($txt, 'not an image', $Utf8NoBom)

$uploadOk = (& curl.exe -s -X POST -H ('Authorization: Bearer ' + $admin) -F ('file=@' + $png + ';type=image/png') ($BaseUrl + '/api/upload/image')) -join ''
$uploadOkData = $null; try { $uploadOkData = $uploadOk | ConvertFrom-Json } catch { }
Check-True 'upload.image.ok' ($uploadOkData -ne $null -and $uploadOkData.data.url -like '/uploads/*') ('url=' + $uploadOkData.data.url)
$uploadBad = (& curl.exe -s -X POST -H ('Authorization: Bearer ' + $admin) -F ('file=@' + $txt + ';type=text/plain') ($BaseUrl + '/api/upload/image')) -join ''
Check-True 'upload.bad-type.rejected' ($uploadBad -like '*"code":400*') ''
$uploadMismatch = (& curl.exe -s -X POST -H ('Authorization: Bearer ' + $admin) -F ('file=@' + $png + ';type=text/plain') ($BaseUrl + '/api/upload/image')) -join ''
Check-True 'upload.mime-mismatch.rejected' ($uploadMismatch -like '*"code":400*') ''
# stats requires doc:center, which STAFF already holds -> 200 for every logged-in role
Check 'stats.staff.http' (Api -Method GET -Path '/api/stats/overview' -Token $staff).Status 200

$stats = Api -Method GET -Path '/api/stats/overview' -Token $admin
Check 'stats.http' $stats.Status 200
Check-True 'stats.fields' (($null -ne $stats.Data.data.myDocumentCount) -and ($null -ne $stats.Data.data.myFavoriteCount) -and ($null -ne $stats.Data.data.publishedCount)) ''

# -----------------------------------------------------------------------------
Write-Host ''
Write-Host '[SQL budget - constants, no N+1]'
# -----------------------------------------------------------------------------
if ($AppLog -and (Test-Path -LiteralPath $AppLog)) {
    Count-Sql 'documents-lastpage' '/api/documents?pageSize=100' $admin 3
    Count-Sql 'documents-fullpage' '/api/documents?pageSize=1&pageNum=1' $admin 4
    Count-Sql 'documents-category' '/api/documents?categoryId=1&pageSize=100' $admin 5
    Count-Sql 'mine' '/api/documents/mine?pageSize=100' $admin 3
    Count-Sql 'trash' '/api/documents/trash?pageSize=100' $admin 1
    Count-Sql 'favorites' '/api/favorites?pageSize=100' $admin 3
    Count-Sql 'review' '/api/review/documents?pageSize=100' $docAdmin 3
    Count-Sql 'category-tree' '/api/categories/tree' $admin 1
    Count-Sql 'tags' '/api/tags?pageSize=100' $admin 1
    Count-Sql 'stats' '/api/stats/overview' $admin 1
} else {
    Write-Host '  [INFO] -AppLog not given: SQL budget checks skipped'
}

Write-Host ''
Write-Host '============================================================='
Write-Host (' RESULT: PASS=' + $script:Pass + '  FAIL=' + $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host ' Failed checks:'
    foreach ($f in $script:Failures) { Write-Host ('   - ' + $f) }
}
Write-Host '============================================================='
exit $script:Fail
