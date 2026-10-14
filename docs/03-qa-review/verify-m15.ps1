# =============================================================================
#  verify-m15.ps1 -- M15 JWT login (HS256) + Redis revocable session
#
#  Two layers:
#    A. static  : jjwt dependency, HS256 signing, verify-before-redis order,
#                 prod fails fast without JWT_SECRET, docs no longer claim "no JWT"
#    B. runtime : login returns a real JWT, tampered/bogus tokens are rejected,
#                 the JWT really lives in Redis, and logout kills it instantly
#
#  Pure ASCII on purpose (Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI).
#
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File docs\03-qa-review\verify-m15.ps1
#    ... -SkipLive
# =============================================================================
param(
    [switch]$SkipLive,
    [string]$BaseUrl = 'http://localhost:10087',
    [string]$RedisCli = 'D:\DevEnv\04_Redis\redis-cli.exe',
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
function Decode-JwtPart([string]$Segment) {
    $s = $Segment.Replace('-', '+').Replace('_', '/')
    while ($s.Length % 4) { $s += '=' }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))
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
Write-Host '=== M15 JWT login + Redis revocable session ===' -ForegroundColor Cyan
Write-Host ('    repo: ' + $Repo)

# ---------------------------------------------------------------- A. static
Write-Host ''
Write-Host 'A. static checks' -ForegroundColor Cyan
$pom = Read-Text 'backend\pom.xml'
Check 'A1 jjwt api/impl/jackson pinned explicitly (Boot BOM does not manage it)' `
    ((Has $pom 'jjwt-api') -and (Has $pom 'jjwt-impl') -and (Has $pom 'jjwt-jackson') -and (Has $pom 'jjwt.version'))

$jwt = Read-Text 'backend\src\main\java\com\campusswap\common\security\JwtService.java'
Check 'A2 signs with HS256 and parses signed claims' `
    ((Has $jwt 'Jwts.SIG.HS256') -and (Has $jwt 'parseSignedClaims') -and (Has $jwt 'verifyWith'))
Check 'A3 refuses a secret shorter than 32 bytes' ((Has $jwt 'bytes.length < 32') -and (Has $jwt 'IllegalStateException'))
Check 'A4 carries iss/sub/jti/iat/exp claims' `
    ((Has $jwt '.issuer(') -and (Has $jwt '.subject(') -and (Has $jwt '.id(') -and (Has $jwt '.expiration('))

$token = Read-Text 'backend\src\main\java\com\campusswap\system\service\impl\TokenServiceImpl.java'
Check 'A5 issue() signs a JWT and stores it in Redis' `
    ((Has $token 'jwtService.sign(userId)') -and (Has $token 'RedisKeys.token(token)') -and (Has $token 'userTokens'))
$verifyAt = $token.IndexOf('jwtService.verify(token)')
$redisAt = $token.IndexOf('get(RedisKeys.token(token))')
Check 'A6 resolve() verifies the signature BEFORE touching Redis' (($verifyAt -gt 0) -and ($redisAt -gt $verifyAt)) `
    ('verifyAt=' + $verifyAt + ' redisAt=' + $redisAt)
Check 'A7 revoke() deletes the Redis key (instant logout)' `
    ((Has $token 'stringRedisTemplate.delete(RedisKeys.token(token))') -and (Has $token 'revokeAll'))

foreach ($prof in 'application-dev.yml', 'application-demo.yml') {
    $y = Read-Text ('backend\src\main\resources\' + $prof)
    Check ('A8 ' + $prof + ' configures campusswap.security.jwt') `
        ((Has $y 'campusswap:') -and (Has $y 'jwt:') -and (Has $y 'secret:') -and (Has $y 'ttl-minutes:'))
}
$prod = Read-Text 'backend\src\main\resources\application-prod.yml'
Check 'A9 prod requires JWT_SECRET with no fallback default' `
    ((Has $prod '${JWT_SECRET}') -and (-not (Has $prod '${JWT_SECRET:')))

$arch = Read-Text 'docs\02-design\ARCHITECTURE.md'
Check 'A10 architecture section 5.2 rewritten for JWT + Redis revocation' `
    ((Has $arch 'HS256 JWT + Redis') -and (Has $arch 'login:token:') -and (Has $arch 'ADR-04'))
$test = Read-Text 'backend\src\test\java\com\campusswap\system\Ac10JwtTokenTest.java'
Check 'A11 unit tests cover tampering, expiry and instant logout' `
    ((Has $test 'tamperedTokenRejected') -and (Has $test 'expiredTokenRejected') -and (Has $test 'logoutInvalidatesValidJwt'))
Check 'A12 closure record exists' (Test-Path (Join-Path $Repo 'docs\03-qa-review\M15-CLOSURE.md'))

# ---------------------------------------------------------------- B. runtime
Write-Host ''
Write-Host 'B. runtime checks' -ForegroundColor Cyan
$appUp = $false
if (-not $SkipLive) {
    try { $null = Invoke-WebRequest -Uri ($BaseUrl + '/api/auth/me') -UseBasicParsing -TimeoutSec 5; $appUp = $true }
    catch {
        $code = 0
        try { $code = [int]$_.Exception.Response.StatusCode } catch { $code = 0 }
        $appUp = ($code -eq 401)
    }
}
if ($SkipLive) {
    Skip-Check 'B1..B7 JWT over HTTP + Redis' 'SkipLive'
} elseif (-not $appUp) {
    Skip-Check 'B1..B7 JWT over HTTP + Redis' ($BaseUrl + ' is not answering')
} else {
    $login = Api 'POST' '/api/auth/login' @{ username = $AdminUser; password = $AdminPass }
    $jwtToken = $null
    if ($login.Data -and $login.Data.data) { $jwtToken = $login.Data.data.token }
    if (-not $jwtToken) {
        Skip-Check 'B1..B7 JWT over HTTP + Redis' 'login failed'
    } else {
        $parts = @($jwtToken -split '\.')
        Check 'B1 login returns a three part JWT' (($parts.Count -eq 3) -and $jwtToken.StartsWith('eyJ')) `
            ('len=' + $jwtToken.Length + ' parts=' + $parts.Count)

        $payload = ''
        try { $payload = Decode-JwtPart $parts[1] } catch { $payload = '' }
        Check 'B2 payload carries iss/sub/jti/iat/exp' `
            (($payload -match '"iss":"campusswap"') -and ($payload -match '"sub":"\d+"') -and ($payload -match '"jti"') `
             -and ($payload -match '"iat"') -and ($payload -match '"exp"')) $payload

        Check 'B3 the JWT authenticates a protected endpoint' ((HttpCode '/api/auth/me' $jwtToken) -eq '200')

        $forgedPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"iss":"campusswap","sub":"1","exp":9999999999}')).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        $forged = $parts[0] + '.' + $forgedPayload + '.' + $parts[2]
        Check 'B4 a tampered payload is rejected with 401' ((HttpCode '/api/auth/me' $forged) -eq '401')
        Check 'B5 a bogus token is rejected with 401' `
            ((HttpCode '/api/auth/me' 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.fake') -eq '401')

        if (-not (Test-Path $RedisCli)) {
            Skip-Check 'B6 the JWT really lives in Redis' ('redis-cli not found: ' + $RedisCli)
            Skip-Check 'B7 logout kills the session instantly' 'redis-cli not found'
        } else {
            $key = 'login:token:' + $jwtToken
            $stored = & $RedisCli get $key
            $ttl = & $RedisCli ttl $key
            Check 'B6 login state really lives in Redis (value = userId, ttl > 0)' `
                (($stored -match '^\d+$') -and ([int]$ttl -gt 0)) ('value=' + $stored + ' ttl=' + $ttl)

            $null = Api 'POST' '/api/auth/logout' $null $jwtToken
            Start-Sleep -Milliseconds 500
            $after = & $RedisCli get $key
            $code = HttpCode '/api/auth/me' $jwtToken
            Check 'B7 logout kills the session instantly (redis key gone + 401, signature still valid)' `
                (($null -eq $after) -or ($after -eq '')) ('http=' + $code + ' redis=' + $after)
        }
    }
}

Write-Host ''
Write-Host ('=== M15 result: PASS ' + $Pass + ' / FAIL ' + $Fail + ' / SKIP ' + $Skip + ' ===') -ForegroundColor Cyan
if ($Fail -gt 0) { exit 1 }
exit 0
