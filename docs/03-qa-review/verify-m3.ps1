# verify-m3.ps1 -- M3 backend skeleton + RBAC self-check (pure ASCII output only!)
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File verify-m3.ps1
# Re-runnable, no database/service needed (static + cross-file consistency checks).
# NOTE: keep this file pure ASCII -- PS 5.1 reads BOM-less UTF-8 as ANSI and CJK literals break the parser.

param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fails = 0

function Check {
    param([string]$Nm, [bool]$Ok, [string]$Detail)
    if ($Ok) { $script:pass++; Write-Host ("[PASS] {0} -- {1}" -f $Nm, $Detail) }
    else { $script:fails++; Write-Host ("[FAIL] {0} -- {1}" -f $Nm, $Detail) -ForegroundColor Red }
}

$backend = Join-Path $RepoRoot 'backend'
$javaRoot = Join-Path $backend 'src\main\java\com\campusswap'
$resRoot = Join-Path $backend 'src\main\resources'
$sqlDir = Join-Path $backend 'sql'
$docsDir = Join-Path $RepoRoot 'docs'
$qaDir = Join-Path $docsDir '03-qa-review'

function Read-All {
    param([string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}
function Java-Files {
    return @(Get-ChildItem -LiteralPath $javaRoot -Recurse -Filter '*.java' -File)
}

Write-Host '============================================================='
Write-Host ' CampusSwap M3 self-check (backend skeleton + RBAC)'
Write-Host (" RepoRoot = " + $RepoRoot)
Write-Host '============================================================='

# --- C1: pom coordinates and Java level --------------------------------------
$pom = Read-All (Join-Path $backend 'pom.xml')
Check 'C1  pom parent = spring-boot-starter-parent 4.1.1 + java 17' `
    (($pom -like '*<artifactId>spring-boot-starter-parent</artifactId>*') -and
     ($pom -like '*<version>4.1.1</version>*') -and
     ($pom -like '*<java.version>17</java.version>*')) 'pom.xml'

# --- C2: Boot 4 starter naming (webmvc, not web) ------------------------------
$hasWebmvc = $pom -like '*<artifactId>spring-boot-starter-webmvc</artifactId>*'
$hasLegacyWeb = $pom -like '*<artifactId>spring-boot-starter-web</artifactId>*'
Check 'C2  Boot 4 starter naming: webmvc present, legacy -web absent' `
    ($hasWebmvc -and (-not $hasLegacyWeb)) ('webmvc=' + $hasWebmvc + ' legacyWeb=' + $hasLegacyWeb)

# --- C3: required dependencies ----------------------------------------------
$need = @('spring-boot-starter-data-jpa', 'spring-boot-starter-data-redis', 'spring-boot-starter-validation',
    'mysql-connector-j', 'hutool-all', 'lombok', 'spring-boot-starter-test', 'spring-boot-starter-webmvc-test')
$missing = @($need | Where-Object { $pom -notlike ('*' + $_ + '*') })
Check 'C3  all required dependencies declared' ($missing.Count -eq 0) ('missing=' + ($missing -join ','))

# --- C4: configuration safety ------------------------------------------------
$appYml = Read-All (Join-Path $resRoot 'application.yml')
$devYml = Read-All (Join-Path $resRoot 'application-dev.yml')
$prodYml = Read-All (Join-Path $resRoot 'application-prod.yml')
Check 'C4a ddl-auto none + open-in-view false (dev)' `
    (($devYml -like '*ddl-auto: none*') -and ($devYml -like '*open-in-view: false*')) 'application-dev.yml'
Check 'C4b ports dev=10087 prod=10180' `
    (($appYml -like '*port: 10087*') -and ($prodYml -like '*port: 10180*')) 'application(-dev/-prod).yml'
Check 'C4c prod profile has no credential default' `
    ((-not ($prodYml -like '*CampusSwap@2026*')) -and ($prodYml -like '*${DB_PASSWORD}*')) 'application-prod.yml'
Check 'C4d redis repository scanning disabled' ($appYml -like '*repositories:*enabled: false*') 'application.yml'

# --- C5: package layout ------------------------------------------------------
$expectPkgs = @('common\api', 'common\exception', 'common\security', 'common\util', 'config',
    'entity', 'entity\enums', 'system\controller', 'system\service', 'system\service\impl',
    'system\repository', 'system\dto', 'system\vo', 'document\repository')
$missPkg = @($expectPkgs | Where-Object { -not (Test-Path -LiteralPath (Join-Path $javaRoot $_)) })
Check 'C5  package layout present' ($missPkg.Count -eq 0) ('missing=' + ($missPkg -join ','))

# --- C6: entity count and table-name cross check -----------------------------
$schema = Read-All (Join-Path $sqlDir 'schema.sql')
$schemaTables = @([regex]::Matches($schema, 'CREATE TABLE `([a-z_]+)`') | ForEach-Object { $_.Groups[1].Value } | Sort-Object)
$entityFiles = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'entity') -Filter '*.java' -File)
$tableNames = @()
foreach ($f in $entityFiles) {
    $c = Read-All $f.FullName
    $m = [regex]::Match($c, '@Table\(name = "([a-z_]+)"\)')
    if ($m.Success) { $tableNames += $m.Groups[1].Value }
}
$tableNames = @($tableNames | Sort-Object)
$diff = @(Compare-Object $schemaTables $tableNames)
Check 'C6a 14 @Table entities == 14 schema tables' `
    (($tableNames.Count -eq 14) -and ($schemaTables.Count -eq 14) -and ($diff.Count -eq 0)) `
    ('entities=' + $tableNames.Count + ' schema=' + $schemaTables.Count + ' diff=' + $diff.Count)
$baseEntity = Test-Path -LiteralPath (Join-Path $javaRoot 'entity\BaseEntity.java')
Check 'C6b BaseEntity present' $baseEntity 'entity/BaseEntity.java'

# --- C7: forbidden JPA patterns ---------------------------------------------
$allJava = ''
foreach ($f in (Java-Files)) { $allJava += (Read-All $f.FullName) }
$dataCount = ([regex]::Matches($allJava, '(?m)^\s*@Data\s*$')).Count
$eagerCount = ([regex]::Matches($allJava, 'FetchType\.EAGER')).Count
Check 'C7a zero @Data annotations' ($dataCount -eq 0) ('count=' + $dataCount)
Check 'C7b zero FetchType.EAGER' ($eagerCount -eq 0) ('count=' + $eagerCount)

# --- C8: soft delete coverage ------------------------------------------------
# NOTE: count annotation LINES, not raw text -- javadoc mentions "@SQLDelete" too.
$sqlDelete = ([regex]::Matches($allJava, '(?m)^\s*@SQLDelete')).Count
$sqlRestriction = ([regex]::Matches($allJava, '(?m)^\s*@SQLRestriction')).Count
Check 'C8a 8 soft-delete entities carry @SQLDelete + @SQLRestriction' `
    (($sqlDelete -eq 8) -and ($sqlRestriction -eq 8)) ('sqlDelete=' + $sqlDelete + ' sqlRestriction=' + $sqlRestriction)
$ordinal = ([regex]::Matches($allJava, 'EnumType\.ORDINAL')).Count
$stringEnum = ([regex]::Matches($allJava, '(?m)^\s*@Enumerated\(EnumType\.STRING\)')).Count
Check 'C8b 4 enum columns use STRING, never ORDINAL' (($ordinal -eq 0) -and ($stringEnum -eq 4)) `
    ('STRING=' + $stringEnum + ' ORDINAL=' + $ordinal)
$primitiveId = ([regex]::Matches($allJava, 'private\s+long\s+id\s*;')).Count
Check 'C8c no primitive long id' ($primitiveId -eq 0) ('count=' + $primitiveId)

# --- C9: junction entities stay lean ----------------------------------------
$junctions = @('UserRole.java', 'UserPermission.java', 'RolePermission.java', 'DeptRole.java', 'DocumentTagRel.java', 'Favorite.java')
$badJunction = @()
foreach ($j in $junctions) {
    $p = Join-Path $javaRoot ('entity\' + $j)
    if (-not (Test-Path -LiteralPath $p)) { $badJunction += ($j + ':missing'); continue }
    $c = Read-All $p
    if ($c -notlike '*@IdClass*') { $badJunction += ($j + ':noIdClass') }
    if ($c -like '*@SQLRestriction*') { $badJunction += ($j + ':hasRestriction') }
}
Check 'C9  6 junction entities: @IdClass, no soft delete' ($badJunction.Count -eq 0) ('issues=' + ($badJunction -join ','))

# --- C10: repositories -------------------------------------------------------
# NOTE: count real JPA repositories only. M4 added DocumentQueryRepository, a Spring Data
# *fragment* interface (custom Criteria implementation), which does not extend JpaRepository.
$systemRepo = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'system\repository') -Filter '*Repository.java' -File)
$docRepo = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'document\repository') -Filter '*Repository.java' -File)
$allRepos = @($systemRepo + $docRepo)
$repoFiles = @($allRepos | Where-Object { (Read-All $_.FullName) -like '*extends JpaRepository*' })
$fragments = @($allRepos | Where-Object { (Read-All $_.FullName) -notlike '*extends JpaRepository*' })
$notJpa = @($repoFiles | Where-Object { (Read-All $_.FullName) -notlike '*extends JpaRepository*' })
Check 'C10 14 JPA repositories, all extend JpaRepository' `
    (($repoFiles.Count -eq 14) -and ($notJpa.Count -eq 0)) `
    ('count=' + $repoFiles.Count + ' fragments=' + (($fragments | ForEach-Object { $_.Name }) -join ','))
$specRepo = @($repoFiles | Where-Object { (Read-All $_.FullName) -like '*JpaSpecificationExecutor*' })
Check 'C10b dynamic query repos use JpaSpecificationExecutor' ($specRepo.Count -ge 4) ('count=' + $specRepo.Count)

# --- C11: controllers and endpoint inventory --------------------------------
$ctrlDir = Join-Path $javaRoot 'system\controller'
$ctrlFiles = @(Get-ChildItem -LiteralPath $ctrlDir -Filter '*Controller.java' -File)
$endpoints = @()
$permCount = 0
foreach ($f in $ctrlFiles) {
    $c = Read-All $f.FullName
    $permCount += ([regex]::Matches($c, '@RequiresPermission\(')).Count
    $classPath = [regex]::Match($c, '@RequestMapping\("([^"]+)"\)').Groups[1].Value
    foreach ($m in [regex]::Matches($c, '@(Get|Post|Put|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\))?')) {
        $endpoints += ($m.Groups[1].Value.ToUpper() + ' ' + $classPath + $m.Groups[2].Value)
    }
}
Check 'C11a 5 controllers' ($ctrlFiles.Count -eq 5) ('count=' + $ctrlFiles.Count)
Check 'C11b 26 endpoints (auth4/user6/role6/perm4/dept6)' ($endpoints.Count -eq 26) ('count=' + $endpoints.Count)
Check 'C11c 22 guarded endpoints (@RequiresPermission); auth login/logout/me/password unguarded' ($permCount -eq 22) ('count=' + $permCount)

# --- C12: permission codes used by controllers exist in seed data -----------
$data = Read-All (Join-Path $sqlDir 'data.sql')
$usedCodes = @([regex]::Matches($allJava, '@RequiresPermission\("([a-z:]+)"\)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$unknownCodes = @($usedCodes | Where-Object { $data -notlike ("*'" + $_ + "'*") })
Check 'C12 every @RequiresPermission code exists in sql/data.sql' ($unknownCodes.Count -eq 0) `
    ('used=' + $usedCodes.Count + ' unknown=' + ($unknownCodes -join ','))

# --- C13: controller endpoints match the frozen API specification -----------
$spec = Read-All (Join-Path $docsDir '02-design\API_SPECIFICATION.md')
$notInSpec = @()
foreach ($e in $endpoints) {
    $path = $e.Substring($e.IndexOf(' ') + 1)
    if ($spec -notlike ('*' + $path + '*')) { $notInSpec += $e }
}
Check 'C13 all 26 endpoints documented in API_SPECIFICATION.md' ($notInSpec.Count -eq 0) ('missing=' + ($notInSpec -join ','))

# --- C14: the four auth gates -----------------------------------------------
$gates = @('common\security\LoginInterceptor.java', 'common\security\SecurityContext.java',
    'common\security\PermissionAspect.java', 'common\security\RequiresPermission.java',
    'common\security\BearerToken.java', 'common\security\RedisKeys.java')
$missGate = @($gates | Where-Object { -not (Test-Path -LiteralPath (Join-Path $javaRoot $_)) })
Check 'C14 four auth gates + shared helpers present' ($missGate.Count -eq 0) ('missing=' + ($missGate -join ','))

# --- C15: global exception handler coverage ---------------------------------
$geh = Read-All (Join-Path $javaRoot 'common\exception\GlobalExceptionHandler.java')
$handled = @('BusinessException', 'MethodArgumentNotValidException', 'BindException', 'ConstraintViolationException',
    'HandlerMethodValidationException', 'MethodArgumentTypeMismatchException', 'HttpMessageNotReadableException',
    'DataIntegrityViolationException', 'MaxUploadSizeExceededException', 'NoResourceFoundException', 'Exception')
$missHandler = @($handled | Where-Object { $geh -notlike ('*@ExceptionHandler(' + $_ + '.class)*') })
Check 'C15 GlobalExceptionHandler covers the documented types' ($missHandler.Count -eq 0) `
    ('handled=' + ($handled.Count - $missHandler.Count) + '/' + $handled.Count + ' missing=' + ($missHandler -join ','))

# --- C16: Redis key discipline ----------------------------------------------
$redisKeys = Read-All (Join-Path $javaRoot 'common\security\RedisKeys.java')
$prefixes = @('login:token:', 'user:tokens:', 'perm:user:', 'view:doc:')
$missPrefix = @($prefixes | Where-Object { $redisKeys -notlike ('*' + $_ + '*') })
Check 'C16 RedisKeys declares all 4 prefixes' ($missPrefix.Count -eq 0) ('missing=' + ($missPrefix -join ','))
$hardcoded = @(Java-Files | Where-Object {
        $c = Read-All $_.FullName
        ($c -like '*"login:token:"*' -or $c -like '*"perm:user:"*') -and ($_.Name -ne 'RedisKeys.java')
    })
Check 'C16b no hardcoded redis key literal outside RedisKeys' ($hardcoded.Count -eq 0) ('files=' + (($hardcoded | ForEach-Object { $_.Name }) -join ','))

# --- C17: cache eviction happens after commit -------------------------------
$txUtil = Test-Path -LiteralPath (Join-Path $javaRoot 'common\util\TxUtil.java')
$userImpl = Read-All (Join-Path $javaRoot 'system\service\impl\UserServiceImpl.java')
$roleImpl = Read-All (Join-Path $javaRoot 'system\service\impl\RoleServiceImpl.java')
$deptImpl = Read-All (Join-Path $javaRoot 'system\service\impl\DeptServiceImpl.java')
$afterCommit = ([regex]::Matches($allJava, 'TxUtil\.afterCommit')).Count
Check 'C17 cache/token invalidation wrapped in TxUtil.afterCommit' `
    ($txUtil -and ($afterCommit -ge 6) -and ($userImpl -like '*TxUtil.afterCommit*') -and
     ($roleImpl -like '*TxUtil.afterCommit*') -and ($deptImpl -like '*TxUtil.afterCommit*')) `
    ('TxUtil=' + $txUtil + ' calls=' + $afterCommit)

# --- C18: no unpaged findAll in services ------------------------------------
$svcImpl = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'system\service\impl') -Filter '*.java' -File)
$unpaged = @($svcImpl | Where-Object { (Read-All $_.FullName) -like '*findAll()*' })
Check 'C18 no unpaged findAll() in service impls' ($unpaged.Count -eq 0) ('files=' + (($unpaged | ForEach-Object { $_.Name }) -join ','))

# --- C19: JOIN FETCH only on to-one associations (collection fetch breaks paging) ---
# The forbidden combo is Page<Entity> + collection JOIN FETCH (Hibernate HHH000104).
# A single-result JOIN FETCH on a @ManyToOne (Document.category) is the documented approach.
$badFetch = @()
foreach ($f in $allRepos) {
    $code = @(Get-Content -LiteralPath $f.FullName | Where-Object { $_ -notmatch '^\s*(\*|//|/\*)' }) -join "`n"
    foreach ($m in [regex]::Matches($code, 'join fetch\s+([A-Za-z0-9_.]+)')) {
        $target = $m.Groups[1].Value
        if ($target -notmatch '\.category$') { $badFetch += ($f.Name + ':' + $target) }
    }
}
Check 'C19 no collection JOIN FETCH (only to-one category fetch allowed)' ($badFetch.Count -eq 0) `
    ('violations=' + ($badFetch -join ','))

# --- C20: ancestors matching by full path segment ---------------------------
$permRepo = Read-All (Join-Path $javaRoot 'system\repository\PermissionRepository.java')
$deptRepo = Read-All (Join-Path $javaRoot 'system\repository\DeptRepository.java')
Check 'C20 tree descendant query matches full path segments (not naive LIKE)' `
    (($permRepo -like '*ancestors = :path*') -and ($permRepo -like "*concat(:path, ',%')*") -and
     ($deptRepo -like '*ancestors = :path*') -and ($deptRepo -like "*concat(:path, ',%')*")) `
    'PermissionRepository + DeptRepository'

# --- C21: RBAC merge is a single SQL ---------------------------------------
Check 'C21 RBAC merge = one native SQL (union of role/user grants)' `
    (($permRepo -like '*findEffectivePermCodes*') -and ($permRepo -like '*nativeQuery = true*') -and
     ($permRepo -like '*UNION*')) 'PermissionRepository.findEffectivePermCodes'

# --- C22: QA scripts present and pure ASCII --------------------------------
$scripts = @('verify-m0.ps1', 'verify-m1.ps1', 'verify-api-spec.ps1', 'verify-m2.ps1', 'verify-db-deep.ps1',
    'verify-m3.ps1', 'verify-m3-http.ps1')
$missScript = @()
$nonAsciiScript = @()
foreach ($s in $scripts) {
    $p = Join-Path $qaDir $s
    if (-not (Test-Path -LiteralPath $p)) { $missScript += $s; continue }
    $bytes = [System.IO.File]::ReadAllBytes($p)
    if (@($bytes | Where-Object { $_ -gt 127 }).Count -gt 0) { $nonAsciiScript += $s }
}
Check 'C22a 7 QA scripts present' ($missScript.Count -eq 0) ('missing=' + ($missScript -join ','))
Check 'C22b QA scripts are pure ASCII' ($nonAsciiScript.Count -eq 0) ('nonAscii=' + ($nonAsciiScript -join ','))

# --- C23: MASTER-PLAN M3 tasks all ticked ----------------------------------
$plan = Read-All (Join-Path $docsDir 'MASTER-PLAN.md')
$m3 = [regex]::Match($plan, '(?s)### M3 .*?(?=### M4)').Value
$openM3 = @([regex]::Matches($m3, '(?m)^- \[ \] \*\*(T3\.\d+)\*\*') | ForEach-Object { $_.Groups[1].Value })
Check 'C23 all M3 tasks ticked in MASTER-PLAN.md' ($openM3.Count -eq 0) ('open=' + ($openM3 -join ','))

# --- C24: sources compile-clean last time (target/classes freshness) -------
$classesDir = Join-Path $backend 'target\classes\com\campusswap'
$classFiles = @()
if (Test-Path -LiteralPath $classesDir) { $classFiles = @(Get-ChildItem -LiteralPath $classesDir -Recurse -Filter '*.class' -File) }
Check 'C24 build output present (target/classes)' ($classFiles.Count -ge 50) ('class files=' + $classFiles.Count)

Write-Host ''
Write-Host '============================================================='
Write-Host (" RESULT: PASS=" + $script:pass + "  FAIL=" + $script:fails)
Write-Host '============================================================='
exit $script:fails
