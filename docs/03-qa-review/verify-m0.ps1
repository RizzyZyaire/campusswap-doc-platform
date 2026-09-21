# verify-m0.ps1 -- M0 requirement-freeze self-check (ASCII output only)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File verify-m0.ps1
# Re-runnable. Exit code 0 = all checks passed, 1 = at least one failed.

param(
  [string]$Repo = 'D:\DevEnv\projects\campusswap'
)

$ErrorActionPreference = 'Stop'
$req  = Join-Path $Repo 'docs\01-requirements'
$pUs  = Join-Path $req 'USER_STORIES.md'
$pPrd = Join-Path $req 'PRD.md'
$pGlo = Join-Path $req 'GLOSSARY.md'

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

foreach ($p in @($pUs, $pPrd, $pGlo)) {
  if (-not (Test-Path -LiteralPath $p)) { Write-Host "MISSING: $p"; exit 1 }
}

$us  = [IO.File]::ReadAllText($pUs,  [Text.Encoding]::UTF8)
$prd = [IO.File]::ReadAllText($pPrd, [Text.Encoding]::UTF8)
$glo = [IO.File]::ReadAllText($pGlo, [Text.Encoding]::UTF8)

function CountOf {
  param([string]$Text, [string]$Pattern)
  return ([regex]::Matches($Text, $Pattern)).Count
}

# --- C1..C4: user stories / BDD -------------------------------------------------
$storyCount = CountOf $us '(?m)^### US-\d\d '
Check 'C1 story-count' ($storyCount -eq 8) "expected 8, got $storyCount"

$acCount = CountOf $us '\*\*AC-\d\d\.\d'
Check 'C2 ac-count' ($acCount -eq 24) "expected 24, got $acCount"

$given = CountOf $us '\*\*Given\*\*'
$when  = CountOf $us '\*\*When\*\*'
$then  = CountOf $us '\*\*Then\*\*'
Check 'C3 gwt-triplets' (($given -eq $acCount) -and ($when -eq $acCount) -and ($then -eq $acCount)) "Given=$given When=$when Then=$then (must equal AC count $acCount)"

$happyWord = -join @([char]0x6B63, [char]0x5E38, [char]0x6D41)   # "happy path" marker (CJK)
$edgeWord  = -join @([char]0x5F02, [char]0x5E38, [char]0x6D41)   # "edge path" marker  (CJK)

# story blocks live strictly between the "## 2." and "## 3." headings
$iSec2 = $us.IndexOf('## 2.')
$iSec3 = $us.IndexOf('## 3.')
if (($iSec2 -lt 0) -or ($iSec3 -le $iSec2)) { Write-Host 'FAIL: cannot locate section 2/3 boundaries'; exit 1 }
$storyArea = $us.Substring($iSec2, $iSec3 - $iSec2)

$blocks = [regex]::Split($storyArea, '(?m)^### US-')
$badStory = @()
for ($i = 1; $i -lt $blocks.Count; $i++) {
  $b = $blocks[$i]
  $id = 'US-' + ([regex]::Match($b, '^(\d\d)')).Groups[1].Value
  $n  = ([regex]::Matches($b, '\*\*AC-\d\d\.\d')).Count
  $happy = ([regex]::Matches($b, [regex]::Escape($happyWord))).Count
  $edge  = ([regex]::Matches($b, [regex]::Escape($edgeWord))).Count
  if (($n -ne 3) -or ($happy -ne 1) -or ($edge -ne 2)) { $badStory += "$id(ac=$n,happy=$happy,edge=$edge)" }
}
Check 'C4 per-story-3ac-1happy-2edge' ($badStory.Count -eq 0) ("violations: " + ($(if ($badStory.Count) { $badStory -join ',' } else { 'none' })))

# --- C5..C7: PRD ---------------------------------------------------------------
$brCount = CountOf $prd '(?m)^\| BR-\d\d \|'
Check 'C5 business-rules' ($brCount -ge 20) "expected >=20 (BR-01..BR-24), got $brCount"

$permMatch = [regex]::Matches($prd, '(?m)^\| `((?:doc|sys):[^`]+)` \|')
$permCodes = @()
foreach ($m in $permMatch) { $permCodes += $m.Groups[1].Value }
$permUnique = ($permCodes | Sort-Object -Unique)
Check 'C6 perm-code-count' ($permUnique.Count -eq 39) "expected 39 unique, got $($permUnique.Count)"

$missing = @()
foreach ($m in [regex]::Matches($us, '`((?:doc|sys):[^`]+)`')) {
  $code = $m.Groups[1].Value
  if ($permUnique -notcontains $code) { $missing += $code }
}
Check 'C7 us-perm-exists-in-prd' ($missing.Count -eq 0) ("unresolved codes: " + ($(if ($missing.Count) { ($missing | Sort-Object -Unique) -join ',' } else { 'none' })))

$f1 = CountOf $prd '(?m)^\| F1-\d\d '
$f2 = CountOf $prd '(?m)^\| F2-\d\d '
Check 'C8 feature-points' (($f1 -ge 16) -and ($f2 -ge 20)) "F1=$f1 (>=16), F2=$f2 (>=20)"

# --- C9..C11: GLOSSARY ---------------------------------------------------------
$tblMatch = [regex]::Matches($glo, '(?m)^\| [^|]+ \| [A-Za-z]+ \| `((?:sys|doc)_[a-z_]+)` \| `[A-Za-z]+` \|')
$tables = @()
foreach ($m in $tblMatch) { $tables += $m.Groups[1].Value }
$tablesU = ($tables | Sort-Object -Unique)
Check 'C9 table-count-14' ($tablesU.Count -eq 14) "expected 14 unique, got $($tablesU.Count)"

$missingInPrd = @()
foreach ($t in $tablesU) { if ($prd -notmatch [regex]::Escape($t)) { $missingInPrd += $t } }
Check 'C10 tables-listed-in-prd' ($missingInPrd.Count -eq 0) ("not mentioned in PRD: " + ($(if ($missingInPrd.Count) { $missingInPrd -join ',' } else { 'none' })))

$states = @('DRAFT', 'PUBLISHED', 'ARCHIVED', 'TRASH')
$badState = @()
foreach ($s in $states) {
  if (($prd -notmatch $s) -or ($glo -notmatch $s)) { $badState += $s }
}
Check 'C11 state-machine-states' ($badState.Count -eq 0) ("states missing in PRD or GLOSSARY: " + ($(if ($badState.Count) { $badState -join ',' } else { 'none' })))

# --- C12: forbidden aliases in requirement docs (GLOSSARY keeps the list on purpose) -
# deprecated naming may only survive inside quote lines (">") -- i.e. the changelog notes.
# NOTE: this script must stay pure ASCII: PS 5.1 reads BOM-less UTF-8 as ANSI, so CJK
#       literals inside filters decode to garbage and silently stop matching.
function RemoveLegacyContext {
  param([string]$Text)
  return (($Text -split "`n" | Where-Object { $_ -notmatch '^\s*>' }) -join "`n")
}
$usBody  = RemoveLegacyContext $us
$prdBody = RemoveLegacyContext $prd
$badNames = '`is_enabled`|`isEnabled`|`is_deleted`|`isDeleted`|`create_at`|`createAt`|`update_at`|`updateAt`|`author_id`|`role_code`|`perm_code`|`perm_type`|`dept_name`|`tag_name`|`category_name`|`sort_num`|`DocumentListVo`|`PageResult`'
$hitUs  = CountOf $usBody  $badNames
$hitPrd = CountOf $prdBody $badNames
Check 'C12 no-forbidden-alias' (($hitUs -eq 0) -and ($hitPrd -eq 0)) "USER_STORIES=$hitUs PRD=$hitPrd (must be 0; changelog/anti-alias lines excluded)"

# --- C13: out-of-scope section present ----------------------------------------
$oCount = CountOf $prd '(?m)^\| O\d+ \|'
Check 'C13 out-of-scope-items' ($oCount -ge 8) "expected >=8 items (O1..O9), got $oCount"

# --- summary ------------------------------------------------------------------
Write-Host ''
Write-Host ("RESULT: pass={0} fail={1}" -f $script:pass, $script:fails)
if ($script:fails -gt 0) { exit 1 } else { Write-Host 'M0 FREEZE CHECKS: ALL GREEN'; exit 0 }
