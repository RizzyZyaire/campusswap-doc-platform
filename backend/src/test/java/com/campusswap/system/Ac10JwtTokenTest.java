package com.campusswap.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusswap.common.security.JwtService;
import com.campusswap.system.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M15 JWT 单测：签发/验签、篡改拒收、换密钥拒收、弱密钥拒绝、过期拒收，以及"登出立即失效"。
 *
 * <p>前五个是纯单元测试（不连任何中间件）；最后一个连真实 Redis，验证老师《作业说明》里
 * "JWT 登录 + 登录状态保存在 Redis + 退出后 Token 立即失效"这三条能同时成立 ——
 * <b>签名有效的 JWT 在登出后也必须立刻 401</b>，这正是"纯 JWT"做不到的地方。</p>
 *
 * @author Zyaire
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("M15 JWT 登录")
class Ac10JwtTokenTest {

    /** 测试密钥（≥32 字节）。 */
    private static final String SECRET = "CampusSwap-Test-HS256-Secret-Key-0123456789-abcdefghijklmn";

    @Autowired
    private TokenService tokenService;

    @Test
    @DisplayName("签发 → 验签：JWT 三段式且 claims 齐全")
    void signAndVerify() {
        JwtService jwt = new JwtService(SECRET, "campusswap", 120);
        String token = jwt.sign(7L);

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(token).startsWith("eyJ");                 // {"alg":... 的 Base64Url
        assertThat(jwt.verify(token)).isEqualTo(7L);

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"iss\":\"campusswap\"").contains("\"sub\":\"7\"")
                .contains("\"jti\"").contains("\"iat\"").contains("\"exp\"");
    }

    @Test
    @DisplayName("篡改 payload → 验签失败（签名对不上）")
    void tamperedTokenRejected() {
        JwtService jwt = new JwtService(SECRET, "campusswap", 120);
        String[] parts = jwt.sign(7L).split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"campusswap\",\"sub\":\"1\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwt.verify(forged)).isNull();
    }

    @Test
    @DisplayName("换密钥签发 → 另一个服务实例验不过（密钥泄露之外的伪造都挡得住）")
    void wrongKeyRejected() {
        JwtService mine = new JwtService(SECRET, "campusswap", 120);
        JwtService other = new JwtService("Another-Key-That-Is-Also-Long-Enough-0123456789-abcdefghij", "campusswap", 120);

        assertThat(mine.verify(other.sign(7L))).isNull();
    }

    @Test
    @DisplayName("签发者不符 → 拒收")
    void issuerMismatchRejected() {
        JwtService mine = new JwtService(SECRET, "campusswap", 120);
        JwtService other = new JwtService(SECRET, "someone-else", 120);

        assertThat(mine.verify(other.sign(7L))).isNull();
    }

    @Test
    @DisplayName("弱密钥（<32 字节）→ 直接拒绝构造，不给出弱签名的机会")
    void shortSecretRejected() {
        assertThatThrownBy(() -> new JwtService("too-short", "campusswap", 120))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("已过期 → 拒收（exp 由 token 自己携带，服务端不必查库）")
    void expiredTokenRejected() {
        JwtService expired = new JwtService(SECRET, "campusswap", -1);
        assertThat(expired.verify(expired.sign(7L))).isNull();
    }

    @Test
    @DisplayName("登出后：签名依然有效，但 Redis 里已删除 → 立即失效")
    void logoutInvalidatesValidJwt() {
        String token = tokenService.issue(1L);
        assertThat(tokenService.resolve(token)).isEqualTo(1L);

        tokenService.revoke(token);

        // 同一串 JWT 再解析：签名没问题，但登录状态已从 Redis 撤销
        assertThat(tokenService.resolve(token)).isNull();
    }

    @Test
    @DisplayName("一键下线：该用户全部 token 立即失效")
    void revokeAllInvalidatesEveryToken() {
        String first = tokenService.issue(2L);
        String second = tokenService.issue(2L);
        assertThat(tokenService.resolve(first)).isEqualTo(2L);
        assertThat(tokenService.resolve(second)).isEqualTo(2L);

        tokenService.revokeAll(2L);

        assertThat(tokenService.resolve(first)).isNull();
        assertThat(tokenService.resolve(second)).isNull();
    }

    @Test
    @DisplayName("Redis 里没有记录（被清库/被踢）→ 即使签名有效也拒收")
    void tokenUnknownToRedisRejected() {
        JwtService jwt = new JwtService(SECRET, "campusswap", 120);
        // 这里用生产密钥配置不一致的"陌生 JWT"模拟：签名层能过、Redis 层没有 → 必须拒绝
        String stranger = jwt.sign(3L);
        assertThat(tokenService.resolve(stranger)).isNull();
    }
}
