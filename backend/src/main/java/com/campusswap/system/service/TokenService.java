package com.campusswap.system.service;

/**
 * Token 服务：<b>HS256 JWT + Redis 撤销名单</b>（老师《作业说明》"JWT 登录；登录状态保存在 Redis；退出后 Token 立即失效"，ARCHITECTURE §5.2）。
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
     * 签发 token：HS256 JWT（{@code header.payload.signature} 三段；claims 见 {@link com.campusswap.common.security.JwtService}），
     * 同时把 JWT 原文写进 Redis（{@code login:token:{token}} → userId，TTL 2 小时）。
     *
     * <p>2026-10-14（M15）由"UUID 去横线 32 位随机串"改造为 JWT；接口签名与调用方不变。</p>
     *
     * @param userId 用户 ID
     * @return token 字符串（JWT）
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
