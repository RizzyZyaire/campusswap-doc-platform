# verify-api-spec.ps1 -- machine self-check for API_SPECIFICATION.md (ASCII output only)
$ErrorActionPreference = 'Stop'
$p = 'D:\DevEnv\projects\campusswap\docs\02-design\API_SPECIFICATION.md'
if (-not (Test-Path -LiteralPath $p)) { 'MISSING FILE'; exit 1 }
$t = [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
$fails = 0
function Chk { param([string]$n, [bool]$ok, [string]$d)
  if ($ok) { Write-Host ("[PASS] {0} -- {1}" -f $n, $d) } else { $script:fails++; Write-Host ("[FAIL] {0} -- {1}" -f $n, $d) -ForegroundColor Red } }

# --- 0. size -------------------------------------------------------------------
$bytes = (Get-Item -LiteralPath $p).Length
$lineCount = ($t -split "`n").Count
Write-Host ("FILE: {0}" -f $p)
Write-Host ("SIZE: {0} lines / {1} bytes" -f $lineCount, $bytes)

# --- 1. leftover anchor --------------------------------------------------------
Chk 'A-no-leftover-anchor' ($t -notmatch '<!-- MODULES -->') 'placeholder removed'

# --- 2. section presence -------------------------------------------------------
foreach ($sec in @('## 1. ', '## 2. ', '## 3. ', '## 4. ', '## 5. ', '## 6. ', '## 7. ', '## 8. ', '## ')) { }
$secOk = $true
foreach ($needle in @('## 1. ', '## 2. ', '## 3. ', '## 4. ', '## 5. ', '## 6. ', '## 7. ', '## 8. ')) {
  if ($t.IndexOf($needle) -lt 0) { $secOk = $false }
}
Chk 'B-eight-sections' $secOk 'required sections 1..8 present'

# --- 3. expected interface list (seq|method|path|perm) -------------------------
$exp = @'
1|POST|/api/auth/login|PUBLIC
2|POST|/api/auth/logout|LOGIN
3|GET|/api/auth/me|LOGIN
4|GET|/api/users|sys:user
5|POST|/api/users|sys:user:add
6|GET|/api/users/{id}|sys:user
7|PUT|/api/users/{id}|sys:user:edit
8|PUT|/api/users/{id}/status|sys:user:disable
9|PUT|/api/users/{id}/password|sys:user:reset
10|GET|/api/roles|sys:role
11|POST|/api/roles|sys:role:add
12|PUT|/api/roles/{id}|sys:role:edit
13|DELETE|/api/roles/{id}|sys:role:delete
14|GET|/api/roles/{id}/permissions|sys:role
15|PUT|/api/roles/{id}/permissions|sys:role:grant
16|GET|/api/permissions/tree|sys:perm
17|POST|/api/permissions|sys:perm:add
18|PUT|/api/permissions/{id}|sys:perm:edit
19|DELETE|/api/permissions/{id}|sys:perm:delete
20|GET|/api/depts/tree|sys:dept
21|POST|/api/depts|sys:dept:add
22|PUT|/api/depts/{id}|sys:dept:edit
23|DELETE|/api/depts/{id}|sys:dept:delete
24|GET|/api/depts/{id}/roles|sys:dept
25|PUT|/api/depts/{id}/roles|sys:role:grant
26|GET|/api/documents|doc:search
27|GET|/api/documents/mine|doc:mine
28|GET|/api/documents/trash|doc:mine
29|POST|/api/documents|doc:create
30|GET|/api/documents/{id}|doc:search
31|PUT|/api/documents/{id}|doc:edit
32|POST|/api/documents/{id}/publish|doc:publish
33|POST|/api/documents/{id}/derive|doc:derive
34|DELETE|/api/documents/{id}|doc:delete
35|POST|/api/documents/{id}/restore|doc:restore
36|DELETE|/api/documents/{id}/destroy|doc:delete
37|GET|/api/documents/{id}/versions|doc:mine
38|POST|/api/documents/{id}/favorite|doc:favorite
39|DELETE|/api/documents/{id}/favorite|doc:favorite
40|GET|/api/favorites|doc:favorite
41|GET|/api/review/documents|doc:review
42|POST|/api/documents/{id}/audit|doc:audit
43|POST|/api/documents/{id}/reject|doc:reject
44|POST|/api/documents/{id}/archive|doc:archive
45|POST|/api/documents/{id}/republish|doc:archive
46|GET|/api/categories/tree|doc:search
47|POST|/api/categories|doc:category:edit
48|PUT|/api/categories/{id}|doc:category:edit
49|DELETE|/api/categories/{id}|doc:category:edit
50|GET|/api/tags|doc:search
51|POST|/api/tags|doc:tag:edit
52|PUT|/api/tags/{id}|doc:tag:edit
53|DELETE|/api/tags/{id}|doc:tag:edit
54|POST|/api/upload/image|doc:upload
55|GET|/api/stats/overview|doc:center
56|PUT|/api/auth/password|LOGIN
57|GET|/api/documents/manage|doc:manage
'@
$expRows = ($exp -split "`n") | Where-Object { $_.Trim() -ne '' }
Chk 'C-expected-count-57' ($expRows.Count -eq 57) ("expected list has {0} rows" -f $expRows.Count)

# --- 4. parse the section 3 master table --------------------------------------
$i3 = $t.IndexOf('## 3. ')
$i4 = $t.IndexOf('## 4. ')
if (($i3 -lt 0) -or ($i4 -le $i3)) { 'FAIL: cannot locate section 3/4'; exit 1 }
$sec3 = $t.Substring($i3, $i4 - $i3)
$pub = -join @([char]0x516C, [char]0x5F00)                                   # "public" (CJK)
$log = -join @([char]0x767B, [char]0x5F55, [char]0x5373, [char]0x53EF)       # "login-only" (CJK)

$rows = @()
foreach ($ln in ($sec3 -split "`n")) {
  if ($ln -match '^\|\s*\d+\s*\|') {
    $f = $ln.Split('|')
    if ($f.Count -ge 11) {
      $rows += ,@{ seq = $f[1].Trim(); method = $f[3].Trim(); path = $f[4].Trim().Trim('`').Trim(); perm = $f[6].Trim().Trim('`').Trim(); dto = $f[7].Trim(); vo = $f[8].Trim(); err = $f[9].Trim() }
    }
  }
}
Chk 'D-master-table-57-rows' ($rows.Count -eq 57) ("parsed {0} rows from section 3" -f $rows.Count)

$mismatch = @()
for ($i = 0; $i -lt [Math]::Min($rows.Count, $expRows.Count); $i++) {
  $e = $expRows[$i].Split('|')
  $r = $rows[$i]
  $wantPerm = $e[3]
  if ($wantPerm -eq 'PUBLIC') { $wantPerm = $pub }
  if ($wantPerm -eq 'LOGIN') { $wantPerm = $log }
  if (($r.seq -ne $e[0]) -or ($r.method -ne $e[1]) -or ($r.path -ne $e[2]) -or ($r.perm -ne $wantPerm)) {
    $mismatch += ("#{0} got[{1} {2} {3}] want[{4} {5} {6}]" -f $e[0], $r.method, $r.path, $r.perm, $e[1], $e[2], $wantPerm)
  }
}
Chk 'E-method-path-perm-exact' ($mismatch.Count -eq 0) ("mismatches: " + $(if ($mismatch.Count) { $mismatch -join ' ; ' } else { 'none' }))

$emptyCells = @()
foreach ($r in $rows) {
  if (($r.perm -eq '') -or ($r.dto -eq '') -or ($r.vo -eq '') -or ($r.err -eq '')) { $emptyCells += ("#" + $r.seq) }
}
Chk 'F-no-empty-cells' ($emptyCells.Count -eq 0) ("rows with empty perm/dto/vo/err: " + $(if ($emptyCells.Count) { $emptyCells -join ',' } else { 'none' }))

# --- 5. per-interface detail blocks -------------------------------------------
$i5 = $t.IndexOf('## 5. ')
$sec4 = $t.Substring($i4, $i5 - $i4)
$heads = ([regex]::Matches($sec4, '(?m)^#### 4\.\d+\.\d+ ')).Count
Chk 'G-detail-blocks-57' ($heads -eq 57) ("found {0} interface headings in section 4" -f $heads)
foreach ($kw in @('**' + 'USE', '**' + 'PERM', '**' + 'ERR')) { }
$cntPerm = ([regex]::Matches($sec4, [regex]::Escape('**' + (-join @([char]0x6743,[char]0x9650,[char]0x70B9)) + '**' + [char]0xFF1A))).Count
$cntIn = ([regex]::Matches($sec4, [regex]::Escape('**' + (-join @([char]0x5165,[char]0x53C2)) + '**'))).Count
$cntOut = ([regex]::Matches($sec4, [regex]::Escape('**' + (-join @([char]0x51FA,[char]0x53C2)) + '**'))).Count
$cntUse = ([regex]::Matches($sec4, [regex]::Escape('**' + (-join @([char]0x7528,[char]0x9014,[char]0x4E0E,[char]0x4EF7,[char]0x503C)) + '**'))).Count
$cntErr = ([regex]::Matches($sec4, [regex]::Escape(-join @([char]0x9519,[char]0x8BEF,[char]0x7801)))).Count
Chk 'H-perm-points-57' ($cntPerm -eq 57) ("perm-point markers: {0}" -f $cntPerm)
Chk 'I-purpose-57' ($cntUse -eq 57) ("purpose markers: {0}" -f $cntUse)
Chk 'J-input-output-57' (($cntIn -eq 57) -and ($cntOut -eq 57)) ("input markers: {0}, output markers: {1}" -f $cntIn, $cntOut)
Chk 'K-errorcode-mentions' ($cntErr -ge 110) ("error-code mentions in section 4: {0} (>=110 expected: table header + rows)" -f $cntErr)

# --- 6. banned aliases: body vs appendix --------------------------------------
$iApp = $t.IndexOf('## ' + (-join @([char]0x9644,[char]0x5F55)) + ' A')
if ($iApp -lt 0) { $iApp = $t.Length }
$body = $t.Substring(0, $iApp)
$app = $t.Substring($iApp)
$banned = @('is_enabled', 'isEnabled', 'role_code', 'roleCode', 'author_id', 'operator_id',
  'update_at', 'updateAt', 'create_by', 'is_deleted', 'del_flag', 'perm_code', 'perm_name', 'perm_type',
  'dept_name', 'category_name', 'tag_name', 'role_name', 'sort_num', 'order_num',
  'DocumentListVo', 'PageResult', 'PageInfo', 'DocumentResp', 'DocumentDTO', 'ApiResponse', 'DocTagRel',
  'sys_login_log', 'DocumentUserVo', 'timestamp', 'UploadVo', 'permissionCount', 'userCount', 'expiresIn',
  'DocumentPageDtoReq', 'RoleCreateDtoReq', 'RoleUpdateDtoReq', 'sortBy', 'PATCH')
$bodyScan = $body -replace 'uk_[a-z_]+', ''          # index names like uk_sys_role_code are legit, not field names
$hitBody = @()
foreach ($b in $banned) { if ($bodyScan -cmatch [regex]::Escape($b)) { $hitBody += $b } }
$hitApp = @()
foreach ($b in $banned) { if ($app -cmatch [regex]::Escape($b)) { $hitApp += $b } }
# 'Result<' must not appear without the Response prefix
$bareResult = ([regex]::Matches($body, '(?<!Response)Result<')).Count
Chk 'L-banned-alias-body-zero' (($hitBody.Count -eq 0) -and ($bareResult -eq 0)) ("body hits: " + $(if ($hitBody.Count) { $hitBody -join ',' } else { 'none' }) + "; bare Result< : $bareResult")
Write-Host ("[INFO] appendix A intentionally keeps {0} deprecated names: {1}" -f $hitApp.Count, ($hitApp -join ','))

$zhBanned = @()
foreach ($cp in @(@(0x8D44, 0x6599), @(0x6587, 0x7AE0), @(0x7B14, 0x8BB0))) {
  $w = -join @([char]$cp[0], [char]$cp[1])
  if ($body -match [regex]::Escape($w)) { $zhBanned += $w }
}
Chk 'M-cjk-synonym-zero' ($zhBanned.Count -eq 0) ("CJK document synonyms found: " + $(if ($zhBanned.Count) { $zhBanned -join ',' } else { 'none' }))

# --- 7. story coverage + page coverage ----------------------------------------
$i6 = $t.IndexOf('## 6. ')
$sec5 = $t.Substring($i5, $i6 - $i5)
$missStory = @()
for ($i = 1; $i -le 8; $i++) { $s = 'US-0' + $i; if ($sec5 -notmatch $s) { $missStory += $s } }
Chk 'N-story-coverage-8' ($missStory.Count -eq 0) ("stories missing in section 5: " + $(if ($missStory.Count) { $missStory -join ',' } else { 'none' }))

$i7 = $t.IndexOf('## 7. ')
$sec6 = $t.Substring($i6, $i7 - $i6)
$nums = @{}
foreach ($m in [regex]::Matches($sec6, '\d+')) { $v = [int]$m.Value; if (($v -ge 1) -and ($v -le 57)) { $nums[$v] = $true } }
$missNum = @()
for ($i = 1; $i -le 57; $i++) { if (-not $nums.ContainsKey($i)) { $missNum += $i } }
Chk 'O-page-mapping-covers-57' ($missNum.Count -eq 0) ("interface numbers missing in section 6: " + $(if ($missNum.Count) { ($missNum | Select-Object -First 20) -join ',' } else { 'none' }))

# --- 8. state machine edges ----------------------------------------------------
$i8 = $t.IndexOf('## 8. ')
$sec7 = $t.Substring($i7, $i8 - $i7)
$missT = @()
for ($i = 1; $i -le 11; $i++) { $tag = '**T' + $i + '**'; if ($sec7 -notmatch [regex]::Escape($tag)) { $missT += ('T' + $i) } }
Chk 'P-state-edges-T1-T11' ($missT.Count -eq 0) ("edges missing: " + $(if ($missT.Count) { $missT -join ',' } else { 'none' }))

# --- 9. response envelope consistency ----------------------------------------
$cCode = ([regex]::Matches($t, '"code":\s*\d{3}')).Count
$cMsg = ([regex]::Matches($t, '"message":')).Count
$cData = ([regex]::Matches($t, '"data":')).Count
Chk 'Q-envelope-consistency' (($cCode -eq $cMsg) -and ($cCode -eq $cData)) ("code=$cCode message=$cMsg data=$cData (must be equal)")

# --- 10. frontend field-contract alignment (GLOSSARY 3.6 / 3.7) --------------
$auditInherit = ([regex]::Matches($t, [regex]::Escape('| **' + (-join @([char]0x7EE7,[char]0x627F)) + ' `AuditVo`** |'))).Count
Chk 'R-auditvo-inheritance-9' ($auditInherit -eq 9) ("VO rows inheriting AuditVo: {0} (expect 9: UserVo/UserInfoVo/RoleVo/PermissionVo/DeptVo/DocumentVo/TagVo/CategoryVo/DocumentVersionVo)" -f $auditInherit)
Chk 'S-no-deleted-in-vo' (($body -notmatch '\|\s*`deleted`\s*\|') -and ($body -notmatch '"deleted":')) 'no deleted field in any VO table or JSON example (body, appendix excluded)'
Chk 'T-authorid-operatorid' (($t -match '`authorId`') -and ($t -match '`operatorId`') -and ($t -match '`authorName`') -and ($t -match '`operatorName`')) 'authorId/authorName + operatorId/operatorName present'
Chk 'U-sort-and-tagIds' (($body -match '\|\s*`sort`\s*\|') -and ($body -match '\|\s*`tagIds`\s*\|') -and ($body -notmatch '`sortBy`')) 'single sort field + tagIds array, no sortBy in body'
Chk 'V-imagevo-statvo' (($t -match '`ImageVo`') -and ($t -match '`StatVo`')) 'ImageVo and StatVo present'
$statusPut = ([regex]::Matches($t, '\| 8 \| ' + (-join @([char]0x7528,[char]0x6237)) + ' \| PUT \|')).Count
Chk 'W-user-status-is-put' ($statusPut -eq 1) ("user status row with PUT in master table: {0}" -f $statusPut)
Chk 'X-roledtoreq-unified' (($t -match '`RoleDtoReq`') -and ($body -notmatch 'Role(Create|Update)DtoReq')) 'RoleDtoReq used for both create and update'

Write-Host ''
if ($fails -gt 0) { Write-Host ("RESULT: FAIL x{0}" -f $fails); exit 1 } else { Write-Host 'API SPEC SELF-CHECK: ALL GREEN'; exit 0 }
