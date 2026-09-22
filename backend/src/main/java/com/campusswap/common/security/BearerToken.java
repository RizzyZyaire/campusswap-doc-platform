package com.campusswap.common.security;

/**
 * {@code Authorization: Bearer <token>} 请求头的解析工具。
 *
 * <p>解析规则只有一处实现：{@link LoginInterceptor}（拦截器路径）与登出接口（被拦截器排除的路径）
 * 共用它，避免两处各写一遍导致口径不一致。</p>
 *
 * @author Zyaire
 */
public final class BearerToken {

    /** 前缀（大小写不敏感）。 */
    private static final String PREFIX = "Bearer ";

    private BearerToken() {
    }

    /**
     * 从 Authorization 头解析 token。
     *
     * @param authorization 请求头原文（可空）
     * @return token；缺失或为空时返回 null
     */
    public static String parse(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            value = value.substring(PREFIX.length()).trim();
        }
        return value.isEmpty() ? null : value;
    }
}
