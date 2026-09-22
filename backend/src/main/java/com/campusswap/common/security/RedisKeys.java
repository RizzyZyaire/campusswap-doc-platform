package com.campusswap.common.security;

import java.time.Duration;

/**
 * Redis 键名与 TTL 的唯一定义处（ARCHITECTURE §7）。
 *
 * <p>约定：所有键必须带业务前缀，禁止裸键名；值统一是字符串或集合，
 * <b>不存序列化 Java 对象</b>（避免类变更导致反序列化炸）。</p>
 *
 * @author Zyaire
 */
public final class RedisKeys {

    /** 登录 token → userId。 */
    public static final String TOKEN_PREFIX = "login:token:";

    /** 用户的全部 token 集合（一键下线用）。 */
    public static final String USER_TOKENS_PREFIX = "user:tokens:";

    /** 用户权限码集合。 */
    public static final String USER_PERM_PREFIX = "perm:user:";

    /** 阅读量去重标记（{@code view:doc:{docId}:{userId}}）。 */
    public static final String VIEW_DEDUP_PREFIX = "view:doc:";

    /** token 与 user:tokens 的 TTL：2 小时（BR-19）。 */
    public static final Duration TOKEN_TTL = Duration.ofHours(2);

    /** 权限缓存 TTL：30 分钟（BR-18）。 */
    public static final Duration PERM_TTL = Duration.ofMinutes(30);

    /** 阅读量去重窗口：30 分钟（BR-09）。 */
    public static final Duration VIEW_TTL = Duration.ofMinutes(30);

    private RedisKeys() {
    }

    /**
     * 拼 token 键。
     *
     * @param token token 字符串
     * @return Redis 键
     */
    public static String token(String token) {
        return TOKEN_PREFIX + token;
    }

    /**
     * 拼用户 token 集合键。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String userTokens(Long userId) {
        return USER_TOKENS_PREFIX + userId;
    }

    /**
     * 拼用户权限缓存键。
     *
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String userPerm(Long userId) {
        return USER_PERM_PREFIX + userId;
    }

    /**
     * 拼阅读量去重键。
     *
     * @param docId  文档 ID
     * @param userId 用户 ID
     * @return Redis 键
     */
    public static String viewDedup(Long docId, Long userId) {
        return VIEW_DEDUP_PREFIX + docId + ":" + userId;
    }
}
