# verify-m1.ps1 -- M1 design-freeze self-check (ASCII output only)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File verify-m1.ps1
# Re-runnable. Exit code 0 = all checks passed, 1 = at least one failed.
# NOTE: sections that DOCUMENT the rejected old naming (changelog / anti-alias tables)
#       anti-alias lists) are excluded on purpose -- otherwise the checker would fire on the
#       very tables that exist to prevent drift.

param(
  [string]$Repo = 'D:\DevEnv\projects\campusswap'
)

$ErrorActionPreference = 'Stop'
$req = Join-Path $Repo 'docs\01-requirements'
$des = Join-Path $Repo 'docs\02-design'
$pGlo = Join-Path $req 'GLOSSARY.md'
$pPrd = Join-Path $req 'PRD.md'
$pUs  = Join-Path $req 'USER_STORIES.md'
$pArc = Join-Path $des 'ARCHITECTURE.md'
$pApi = Join-Path $des 'API_SPECIFICATION.md'
$pUi  = Join-Path $des 'UI_UX_SPECIFICATION.md'

$script:fails = 0
$script:pass  = 0

function Check {
  param([string]$Nm, [bool]$Ok, [string]$Detail)
  if ($Ok) {
    $script:pass++
    Write-Host ("[PASS] {0} -- {1}" -f $Nm, $Detail)
  } else {
    $script:fails++
    Write-Host ("[FAIL] {0} -- {1}" -f $Nm, $Detail) -ForegroundColor Red
  }
}
function CountOf {
  param([string]$Text, [string]$Pattern)
  return ([regex]::Matches($Text, $Pattern)).Count
}
# drop whole sections whose heading carries an ASCII anti-alias/changelog marker.
# NOTE: keep this script pure ASCII -- PS 5.1 reads BOM-less UTF-8 as ANSI, so CJK literals
#       inside filters decode to garbage and silently stop matching.
# ASCII markers used by the docs: 'Anti-alias' (GLOSSARY 6), 'Changelog' (GLOSSARY changelog),
# 'DEPRECATED' (API appendix A), 'baseline' (UI 0.1).
function RemoveLegacyContext {
  param([string]$Text)
  $out = New-Object System.Collections.Generic.List[string]
  $skip = $false
  foreach ($line in ($Text -split "`n")) {
    if ($line -match '^#{2,4}\s') { $skip = ($line -match 'Anti-alias|DEPRECATED|Changelog|baseline') }
    if ($skip) { continue }
    $out.Add($line)
  }
  return ($out -join "`n")
}

foreach ($p in @($pGlo, $pPrd, $pUs, $pArc, $pApi, $pUi)) {
  if (-not (Test-Path -LiteralPath $p)) { Write-Host "MISSING: $p"; exit 1 }
}

$glo = [IO.File]::ReadAllText($pGlo, [Text.Encoding]::UTF8)
$prd = [IO.File]::ReadAllText($pPrd, [Text.Encoding]::UTF8)
$us  = [IO.File]::ReadAllText($pUs,  [Text.Encoding]::UTF8)
$arc = [IO.File]::ReadAllText($pArc, [Text.Encoding]::UTF8)
$api = [IO.File]::ReadAllText($pApi, [Text.Encoding]::UTF8)
$ui  = [IO.File]::ReadAllText($pUi,  [Text.Encoding]::UTF8)

$arcBody = RemoveLegacyContext $arc
$apiBody = RemoveLegacyContext $api
$uiBody  = RemoveLegacyContext $ui
$gloBody = RemoveLegacyContext $glo

# endpoint paths are only counted for KNOWN resources (so "src/api/request.ts" is not an endpoint)
$resources = 'auth|users|roles|permissions|depts|documents|favorites|categories|tags|review|upload|stats'
$pathRe = '/api/(?:' + $resources + ')[a-z0-9{}/_-]*'

# --- C1: three design docs exist and are substantial ---------------------------
$sizes = @(
  @{n = 'ARCHITECTURE'; s = (Get-Item -LiteralPath $pArc).Length },
  @{n = 'API_SPECIFICATION'; s = (Get-Item -LiteralPath $pApi).Length },
  @{n = 'UI_UX_SPECIFICATION'; s = (Get-Item -LiteralPath $pUi).Length }
)
$small = @($sizes | Where-Object { $_.s -lt 10000 } | ForEach-Object { "$($_.n)=$($_.s)B" })
Check 'C1 design-docs-present' ($small.Count -eq 0) ("sizes: " + (($sizes | ForEach-Object { "$($_.n)=$($_.s)B" }) -join ', ') + $(if ($small.Count) { " | too small: " + ($small -join ',') } else { '' }))

# --- C2/C3: every required endpoint appears in the API contract ----------------
$requiredPaths = @(
  '/api/auth/login', '/api/auth/logout', '/api/auth/me',
  '/api/users', '/api/users/{id}', '/api/users/{id}/status', '/api/users/{id}/password',
  '/api/roles', '/api/roles/{id}', '/api/roles/{id}/permissions',
  '/api/permissions/tree', '/api/permissions', '/api/permissions/{id}',
  '/api/depts/tree', '/api/depts', '/api/depts/{id}', '/api/depts/{id}/roles',
  '/api/documents', '/api/documents/mine', '/api/documents/trash', '/api/documents/{id}',
  '/api/documents/{id}/publish', '/api/documents/{id}/derive', '/api/documents/{id}/restore',
  '/api/documents/{id}/destroy', '/api/documents/{id}/audit', '/api/documents/{id}/reject',
  '/api/documents/{id}/archive', '/api/documents/{id}/republish', '/api/documents/{id}/favorite',
  '/api/documents/{id}/versions', '/api/favorites',
  '/api/categories/tree', '/api/categories', '/api/categories/{id}',
  '/api/tags', '/api/tags/{id}', '/api/review/documents', '/api/upload/image', '/api/stats/overview'
)
$missingApi = @()
foreach ($p in $requiredPaths) { if ($api -notlike "*$p*") { $missingApi += $p } }
Check 'C2 api-covers-required-paths' ($missingApi.Count -eq 0) ("required=" + $requiredPaths.Count + " missing=" + $(if ($missingApi.Count) { $missingApi -join ',' } else { '0' }))

$apiPaths  = [regex]::Matches($api, $pathRe) | ForEach-Object { $_.Value } | Sort-Object -Unique
$uiPaths   = [regex]::Matches($ui,  $pathRe) | ForEach-Object { $_.Value } | Sort-Object -Unique
Check 'C3 api-path-count' ($apiPaths.Count -ge 25) ("unique endpoint paths = " + $apiPaths.Count + " (DoD: >= 25)")

# --- C4: UI-spec endpoints are a subset of the API contract --------------------
$uiOrphans = @()
foreach ($p in $uiPaths) { if ($api -notlike "*$p*") { $uiOrphans += $p } }
Check 'C4 ui-endpoints-subset-of-api' ($uiOrphans.Count -eq 0) ("ui paths=" + $uiPaths.Count + " orphans=" + $(if ($uiOrphans.Count) { $uiOrphans -join ',' } else { '0' }))

# --- C5: the 13 front-end routes of UI spec v2 all exist in the UI spec --------
$routes = @('/login', '/workbench', '/docs', '/docs/:id', '/docs/edit/:id?', '/my', '/review',
            '/governance', '/taxonomy', '/admin/users', '/admin/roles', '/admin/org', '/me')
$missingRoutes = @()
foreach ($r in $routes) { if ($ui -notlike "*$r*") { $missingRoutes += $r } }
Check 'C5 ui-v2-thirteen-routes' ($missingRoutes.Count -eq 0) ("required=" + $routes.Count + " missing=" + $(if ($missingRoutes.Count) { $missingRoutes -join ',' } else { '0' }))

# --- C6: all 8 user stories are covered by the API contract --------------------
$missingUs = @()
for ($i = 1; $i -le 8; $i++) {
  $id = 'US-{0:d2}' -f $i
  if ($api -notlike "*$id*") { $missingUs += $id }
}
Check 'C6 api-covers-8-stories' ($missingUs.Count -eq 0) ("missing=" + $(if ($missingUs.Count) { $missingUs -join ',' } else { '0' }))

# --- C7: no forbidden aliases in the design docs (deprecated-naming tables excluded)
$bad = '`is_enabled`|`isEnabled`|`is_deleted`|`isDeleted`|`create_at`|`createAt`|`update_at`|`updateAt`|`author_id`|`role_code`|`perm_code`|`perm_type`|`dept_name`|`tag_name`|`category_name`|`sort_num`|`sortBy`|`DocumentListVo`|`PageResult`|`ApiResponse`|`PATCH /api/users`'
$hitArc = CountOf $arcBody $bad
$hitApi = CountOf $apiBody $bad
$hitUi  = CountOf $uiBody  $bad
Check 'C7 no-forbidden-alias' (($hitArc -eq 0) -and ($hitApi -eq 0) -and ($hitUi -eq 0)) "ARCH=$hitArc API=$hitApi UI=$hitUi (must be 0)"

# --- C8/C9: front-end red lines ----------------------------------------------
$anyHits = (CountOf $ui ':\s*any\b') + (CountOf $ui 'as any') + (CountOf $ui '<any>') + (CountOf $ui 'any\[\]')
Check 'C8 ui-no-any-type' ($anyHits -eq 0) "any usages = $anyHits (teacher red line R6)"

$inlineStyle = CountOf $ui 'style="'
Check 'C9 ui-no-inline-style' ($inlineStyle -eq 0) "inline style attrs = $inlineStyle (teacher red line R6)"

# --- C10: architecture doc completeness ---------------------------------------
$mermaid = CountOf $arc '```mermaid'
$needs = @('perm:user:', 'login:token:', '@RequiresPermission', '@Transactional', 'ResponseResult', 'PageVo', 'SecurityContext')
$arcMissing = @()
foreach ($n in $needs) { if ($arc -notlike "*$n*") { $arcMissing += $n } }
Check 'C10 architecture-completeness' (($mermaid -ge 3) -and ($arcMissing.Count -eq 0)) "mermaid blocks=$mermaid (>=3) missing=" + $(if ($arcMissing.Count) { $arcMissing -join ',' } else { '0' })

# --- C11: every perm code used in design docs exists in PRD (39 codes) --------
$permMatch = [regex]::Matches($prd, '(?m)^\| `((?:doc|sys):[^`]+)` \|')
$permCodes = @(); foreach ($m in $permMatch) { $permCodes += $m.Groups[1].Value }
$permUnique = ($permCodes | Sort-Object -Unique)

$designPerms = @()
foreach ($t in @($api, $ui, $arc)) {
  foreach ($m in [regex]::Matches($t, '(?:doc|sys):[a-z:]+')) { $designPerms += $m.Value.TrimEnd(':') }
}
$designPerms = ($designPerms | Sort-Object -Unique)
$unknownPerms = @()
foreach ($c in $designPerms) {
  $ok = ($permUnique -contains $c)
  if (-not $ok) { foreach ($k in $permUnique) { if ($k.StartsWith($c)) { $ok = $true; break } } }
  if (-not $ok) { $unknownPerms += $c }
}
Check 'C11 perm-codes-known' ($unknownPerms.Count -eq 0) ("prd codes=" + $permUnique.Count + " design codes=" + $designPerms.Count + " unknown=" + $(if ($unknownPerms.Count) { $unknownPerms -join ',' } else { '0' }))

# --- C12: GLOSSARY v2.1 alignment (audit columns, composite keys, VO dictionary)
$gloNeeds = @('created_at', 'created_by', 'updated_at', 'updated_by', 'deleted', 'sys_user_permission', 'doc_document_tag_rel', 'AUTO_INCREMENT', 'AuditVo', 'DocumentSearchDtoReq')
$gloMissing = @()
foreach ($n in $gloNeeds) { if ($glo -notlike "*$n*") { $gloMissing += $n } }
Check 'C12 glossary-v21-alignment' ($gloMissing.Count -eq 0) ("missing=" + $(if ($gloMissing.Count) { $gloMissing -join ',' } else { '0' }))

# --- C13: table count + no legacy table names (deprecated-naming tables excluded)
$tblMatch = [regex]::Matches($glo, '(?m)^\| [^|]+ \| [A-Za-z]+ \| `((?:sys|doc)_[a-z_]+)` \| `[A-Za-z]+` \|')
$tables = @(); foreach ($m in $tblMatch) { $tables += $m.Groups[1].Value }
$tablesU = ($tables | Sort-Object -Unique)
$legacyHit = @()
foreach ($l in @('`sys_login_log`', '`doc_document_tag`')) { if ($gloBody -like "*$l*") { $legacyHit += $l } }
Check 'C13 glossary-14-tables' (($tablesU.Count -eq 14) -and ($legacyHit.Count -eq 0)) ("unique tables=" + $tablesU.Count + " legacy-names=" + $(if ($legacyHit.Count) { $legacyHit -join ',' } else { '0' }))

# --- C14: VO/DTO dictionary registered in GLOSSARY ----------------------------
$voNeeds = @('LoginVo', 'UserInfoVo', 'UserVo', 'UserCreateDtoReq', 'UserStatusDtoReq', 'RoleVo', 'RoleDtoReq',
             'PermissionVo', 'DeptVo', 'DocumentVo', 'DocumentDetailVo', 'DocumentCreateDtoReq',
             'DocumentUpdateDtoReq', 'DocumentSearchDtoReq', 'DocumentVersionVo', 'CategoryVo', 'TagVo', 'ImageVo', 'StatVo', 'PageDtoReq',
             'UserPageDtoReq', 'RolePageDtoReq', 'ReviewPageDtoReq', 'FavoritePageDtoReq', 'TagPageDtoReq',
             'DeptCreateDtoReq', 'DeptUpdateDtoReq', 'CategoryCreateDtoReq', 'CategoryUpdateDtoReq',
             'TagCreateDtoReq', 'TagUpdateDtoReq', 'PermissionCreateDtoReq', 'DocumentAuditDtoReq',
             'DocumentRejectDtoReq', 'DocumentDeriveDtoReq', 'DocumentDestroyDtoReq',
             'RolePermissionVo', 'DeptRoleVo', 'FavoriteVo', 'RolePermissionDtoReq', 'DeptRoleDtoReq',
             'UserPasswordDtoReq', 'DocumentMineDtoReq', 'DocumentTrashDtoReq',
             'DocumentManageDtoReq', 'PasswordChangeDtoReq', 'MatchedIn')
$voMissing = @()
foreach ($v in $voNeeds) { if ($glo -notlike "*$v*") { $voMissing += $v } }
Check 'C14 vo-dictionary-registered' ($voMissing.Count -eq 0) ("registered=" + ($voNeeds.Count - $voMissing.Count) + "/" + $voNeeds.Count + " missing=" + $(if ($voMissing.Count) { $voMissing -join ',' } else { '0' }))

# --- C15: JPA performance rules (courseware 3.1) registered in the design docs -
# NOTE: keep this script pure ASCII -- PowerShell 5.1 reads BOM-less UTF-8 as ANSI and
#       CJK literals break the parser. Match ASCII anchors only.
$jpaNeedsA = @('FetchType.LAZY', '@EntityGraph', 'JpaSpecificationExecutor', 'EXPLAIN ANALYZE', 'idx_doc_status_updated', 'HHH000104')
$jpaMissingA = @(); foreach ($k in $jpaNeedsA) { if ($arc -notlike "*$k*") { $jpaMissingA += $k } }
$jpaNeedsG = @('startTime', 'Set<Tag>', 'updatable')
$jpaMissingG = @(); foreach ($k in $jpaNeedsG) { if ($glo -notlike "*$k*") { $jpaMissingG += $k } }
Check 'C15 jpa-performance-rules' (($jpaMissingA.Count -eq 0) -and ($jpaMissingG.Count -eq 0)) ("ARCH missing=" + $(if ($jpaMissingA.Count) { $jpaMissingA -join ',' } else { '0' }) + " | GLOSSARY missing=" + $(if ($jpaMissingG.Count) { $jpaMissingG -join ',' } else { '0' }))

# --- summary ------------------------------------------------------------------
Write-Host ''
Write-Host ("RESULT: pass={0} fail={1}" -f $script:pass, $script:fails)
if ($script:fails -gt 0) { exit 1 } else { Write-Host 'M1 DESIGN FREEZE CHECKS: ALL GREEN'; exit 0 }
