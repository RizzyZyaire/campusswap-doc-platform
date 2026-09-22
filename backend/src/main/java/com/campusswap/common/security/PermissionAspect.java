package com.campusswap.common.security;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.system.service.PermissionCacheService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 鉴权关卡③：功能权限校验（ARCHITECTURE §5.3 的骨架实现）。
 *
 * <p>拦截所有标注 {@link RequiresPermission} 的方法，取当前登录用户的权限码集合判断；
 * 不通过抛 {@link BusinessException}（{@link ErrorCode#NO_PERMISSION}）→ 全局异常处理器翻成 403。</p>
 *
 * <p>关卡顺序：① 认证（{@link LoginInterceptor}）→ ② 上下文（ThreadLocal）→ ③ 本切面 → ④ 归属（Service 内）。</p>
 *
 * @author Zyaire
 */
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionCacheService permissionCacheService;

    /**
     * 前置校验权限码。
     *
     * @param requiresPermission 方法上的权限注解
     */
    @Before("@annotation(requiresPermission)")
    public void check(RequiresPermission requiresPermission) {
        Long userId = SecurityContext.requireUserId();
        if (!permissionCacheService.hasPermission(userId, requiresPermission.value())) {
            throw new BusinessException(ErrorCode.NO_PERMISSION);
        }
    }
}
