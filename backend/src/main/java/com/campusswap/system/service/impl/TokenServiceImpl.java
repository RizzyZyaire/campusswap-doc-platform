package com.campusswap.system.service.impl;

import com.campusswap.common.security.RedisKeys;
import com.campusswap.system.service.TokenService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Token 服务实现：随机串 + Redis，<b>不用 JWT</b>（JWT 签发后无法主动作废，见 ARCHITECTURE §5.2）。
 *
 * <p>两把键一起维护：{@code login:token:{token}}（鉴权用）与 {@code user:tokens:{userId}}（一键下线用）。
 * 登出/停用/改密直接 DEL，不维护黑名单 —— 删掉即失效，比黑名单更简单也更省内存。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 签发 token。
     *
     * @param userId 用户 ID
     * @return token 字符串
     */
    @Override
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(RedisKeys.token(token), String.valueOf(userId), RedisKeys.TOKEN_TTL);
        String userTokensKey = RedisKeys.userTokens(userId);
        stringRedisTemplate.opsForSet().add(userTokensKey, token);
        stringRedisTemplate.expire(userTokensKey, RedisKeys.TOKEN_TTL);
        return token;
    }

    /**
     * 解析 token。
     *
     * @param token token 字符串
     * @return 用户 ID；无效时返回 null
     */
    @Override
    public Long resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String userId = stringRedisTemplate.opsForValue().get(RedisKeys.token(token));
        if (userId == null) {
            return null;
        }
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException ex) {
            log.warn("Redis 中的 token 值不是合法 userId: {}", userId);
            return null;
        }
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
