package com.campusswap.system.service;

/**
 * Token 服务：随机串 + Redis（<b>不用 JWT</b>，ARCHITECTURE §5.2）。
 *
 * <p>键设计（ARCHITECTURE §7）：</p>
 * <ul>
 *   <li>{@code login:token:{token}} → userId，TTL 2 小时；</li>
 *   <li>{@code user:tokens:{userId}} → 该用户全部 token 的 Set，TTL 2 小时（一键下线用）。</li>
 * </ul>
 *
 * @author Zyaire
 */
public interface TokenService {

    /**
     * 签发 token（UUID 去横线，32 位）。
     *
     * @param userId 用户 ID
     * @return token 字符串
     */
    String issue(Long userId);

    /**
     * 解析 token 对应的用户 ID。
     *
     * @param token token 字符串
     * @return 用户 ID；无效时返回 null
     */
    Long resolve(String token);

    /**
     * 主动失效单个 token（登出）。
     *
     * @param token token 字符串
     */
    void revoke(String token);

    /**
     * 使某用户的全部 token 失效（停用账号、重置密码）。
     *
     * @param userId 用户 ID
     */
    void revokeAll(Long userId);
}
