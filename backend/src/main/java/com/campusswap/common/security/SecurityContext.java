package com.campusswap.common.security;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;

/**
 * 当前请求的登录上下文（ThreadLocal）。
 *
 * <p>由 {@link LoginInterceptor} 在请求进入时写入、请求结束时清理；
 * Service 层通过它取“当前用户 ID”，避免把 userId 从 Controller 一层层往下传。</p>
 *
 * @author Zyaire
 */
public final class SecurityContext {

    /** 当前登录用户 ID；null 表示未登录。 */
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    /** 当前请求携带的 token（由拦截器写入；登出接口走请求头解析，不依赖它）。 */
    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();

    private SecurityContext() {
    }

    /**
     * 写入当前用户上下文。
     *
     * @param userId 用户 ID
     * @param token  登录 token
     */
    public static void set(Long userId, String token) {
        CURRENT_USER_ID.set(userId);
        CURRENT_TOKEN.set(token);
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID；未登录时为 null
     */
    public static Long currentUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 获取当前登录用户 ID（未登录直接 401，供必须登录的场景使用）。
     *
     * <p>正常请求都过了 {@link LoginInterceptor}，走到这里为 null 说明该接口没被拦截器覆盖 ——
     * 属于配置缺陷，但对外只暴露 401，不暴露内部状态。</p>
     *
     * @return 用户 ID
     */
    public static Long requireUserId() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 获取当前请求的 token。
     *
     * @return token；未登录时为 null
     */
    public static String currentToken() {
        return CURRENT_TOKEN.get();
    }

    /**
     * 清理上下文 —— 必须在请求结束的 finally 里调用，否则线程复用会造成越权。
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
        CURRENT_TOKEN.remove();
    }
}
