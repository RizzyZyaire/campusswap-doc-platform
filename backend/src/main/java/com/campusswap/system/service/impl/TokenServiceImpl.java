package com.campusswap.system.service.impl;

import com.campusswap.common.security.JwtService;
import com.campusswap.common.security.RedisKeys;
import com.campusswap.system.service.TokenService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Token 服务实现：<b>HS256 JWT + Redis 撤销名单</b>
 * （老师《作业说明》"JWT 登录；登录状态保存在 Redis；退出后 Token 立即失效"）。
 *
 * <p>三句话对应三件事：</p>
 * <ol>
 *   <li><b>JWT 登录</b>：token 由 {@link JwtService} 用 HS256 签发，自带签名与过期时间；鉴权先验签，
 *       伪造 / 篡改 / 过期的 token 在这一步就被拒（连 Redis 都不用查）；</li>
 *   <li><b>登录状态保存在 Redis</b>：签发同时把 token 原文写入 {@code login:token:{token}} → userId，
 *       并在 {@code user:tokens:{userId}} 集合里登记（TTL 与 JWT 有效期一致，见 ARCHITECTURE §5.2）；</li>
 *   <li><b>退出后 Token 立即失效</b>：登出 / 停用 / 改密直接 DEL Redis 键 —— 签名依然有效的 JWT 也会立刻作废，
 *       这是纯 JWT 做不到、而"JWT + Redis"能同时拿到的好处。</li>
 * </ol>
 *
 * <p>校验顺序刻意是"先验签再查 Redis"：把绝大多数无效请求挡在本地计算里，不给 Redis 添无谓压力。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final StringRedisTemplate stringRedisTemplate;

    private final JwtService jwtService;

    /**
     * 签发 token（HS256 JWT）并写入 Redis。
     *
     * @param userId 用户 ID
     * @return JWT 字符串
     */
    @Override
    public String issue(Long userId) {
        String token = jwtService.sign(userId);
        stringRedisTemplate.opsForValue().set(RedisKeys.token(token), String.valueOf(userId), RedisKeys.TOKEN_TTL);
        String userTokensKey = RedisKeys.userTokens(userId);
        stringRedisTemplate.opsForSet().add(userTokensKey, token);
        stringRedisTemplate.expire(userTokensKey, RedisKeys.TOKEN_TTL);
        return token;
    }

    /**
     * 解析 token：① 验签与过期（本地）② Redis 撤销检查。
     *
     * @param token token 字符串
     * @return 用户 ID；任一环节不通过返回 null
     */
    @Override
    public Long resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Long userId = jwtService.verify(token);
        if (userId == null) {
            return null;
        }
        String cached = stringRedisTemplate.opsForValue().get(RedisKeys.token(token));
        if (cached == null) {
            // 签名有效但 Redis 里没有 → 已登出 / 已被踢 / Redis 侧已过期，一律视为失效
            return null;
        }
        if (!String.valueOf(userId).equals(cached)) {
            log.warn("token 与 Redis 中的 userId 不一致: jwt={}, redis={}", userId, cached);
            return null;
        }
        return userId;
    }

    /**
     * 登出：删除 token 及其在用户 token 集合中的记录。
     *
     * @param token token 字符串
     */
    @Override
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String userId = stringRedisTemplate.opsForValue().get(RedisKeys.token(token));
        stringRedisTemplate.delete(RedisKeys.token(token));
        if (userId != null) {
            stringRedisTemplate.opsForSet().remove(RedisKeys.userTokens(Long.valueOf(userId)), token);
        }
    }

    /**
     * 一键下线：清掉该用户全部 token。
     *
     * @param userId 用户 ID
     */
    @Override
    public void revokeAll(Long userId) {
        if (userId == null) {
            return;
        }
        String userTokensKey = RedisKeys.userTokens(userId);
        Set<String> tokens = stringRedisTemplate.opsForSet().members(userTokensKey);
        if (tokens != null && !tokens.isEmpty()) {
            stringRedisTemplate.delete(tokens.stream().map(RedisKeys::token).toList());
        }
        stringRedisTemplate.delete(userTokensKey);
        log.debug("已强制下线用户 {}，清理 token 数 = {}", userId, tokens == null ? 0 : tokens.size());
    }
}
