package com.campusswap.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 功能权限校验注解（鉴权关卡③）。
 *
 * <p>标在 Controller 方法上，由 {@code PermissionAspect} 在执行前校验当前登录用户是否拥有该权限码；
 * 缺失则返回 403 NO_PERMISSION。写法见 docs/02-design/ARCHITECTURE.md §5.3。</p>
 *
 * <p>用法：{@code @RequiresPermission("doc:publish")}</p>
 *
 * @author Zyaire
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * 所需权限码（对应 sys_permission.code）。
     *
     * @return 权限码，如 {@code sys:role:grant}
     */
    String value();
}
