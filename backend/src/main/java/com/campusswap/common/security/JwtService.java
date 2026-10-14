package com.campusswap.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与校验（老师《作业说明》"JWT 登录"；HS256 对称签名）。
 *
 * <p><b>为什么仍然是"JWT + Redis"两件套</b>（ARCHITECTURE §5.2）：</p>
 * <ul>
 *   <li>JWT 负责**自证身份**：签名 + 过期时间由 token 自己携带，服务端验签即可识别伪造/篡改/过期，
 *       <b>不必查库</b>；</li>
 *   <li>Redis 负责**可撤销**：纯 JWT 的最大软肋是"签发后无法作废"（用户点了退出，token 在过期前依然有效）。
 *       本项目把 JWT 原文写进 Redis，登出/改密/停用时直接删除 → <b>退出后 Token 立即失效</b>，
 *       同时保留"一键踢人"（{@code user:tokens:{userId}} 集合）。两边各司其职，不是重复设计。</li>
 * </ul>
 *
 * <p>Claims 约定：{@code iss}=campusswap、{@code sub}=用户 ID、{@code jti}=一次性随机 ID（排障时可定位具体那次登录）、
 * {@code iat}/{@code exp} 由 jjwt 自动写入。</p>
 *
 * @author Zyaire
 */
@Component
@Slf4j
public class JwtService {

    /** 签名密钥（HS256 要求 ≥ 32 字节；prod 环境必须通过 JWT_SECRET 提供）。 */
    private final SecretKey key;

    /** 签发者标识（校验时一并核对，避免拿到别家 token 来试）。 */
    private final String issuer;

    /** 有效期（与 Redis 里 token 的 TTL 保持一致，见 {@link RedisKeys#TOKEN_TTL}）。 */
    private final java.time.Duration ttl;

    /**
     * 构造。
     *
     * @param secret 密钥明文（至少 32 字节）
     * @param issuer 签发者
     * @param ttlMinutes 有效期（分钟）
     */
    public JwtService(@Value("${campusswap.security.jwt.secret}") String secret,
                      @Value("${campusswap.security.jwt.issuer:campusswap}") String issuer,
                      @Value("${campusswap.security.jwt.ttl-minutes:120}") long ttlMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("campusswap.security.jwt.secret 至少需要 32 字节（HS256 要求），当前 "
                    + bytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
        this.ttl = java.time.Duration.ofMinutes(ttlMinutes);
    }

    /**
     * 签发 token。
     *
     * @param userId 用户 ID
     * @return JWT（{@code header.payload.signature} 三段 Base64Url）
     */
    public String sign(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验 token 并取出用户 ID（不查 Redis）。
     *
     * @param token JWT
     * @return 用户 ID；签名不对/已过期/格式非法/签发者不符时返回 null
     */
    public Long verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            // 不区分"签名错/过期/格式烂"，统一当作无效 token（避免给攻击者区分信号）
            log.debug("JWT 校验未通过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * token 有效期。
     *
     * @return 有效期
     */
    public java.time.Duration ttl() {
        return ttl;
    }
}
