package com.campusswap.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 角色新增/编辑入参（API_SPECIFICATION §4.3.2 / §4.3.3 共用）。
 *
 * <p>{@code code} 创建后不可修改（BR-02）：编辑接口要求传入的 {@code code} 与库中完全一致，否则 400。</p>
 *
 * @param name        角色名称
 * @param code        角色编码（2~32 位大写字母/数字/下划线，全局唯一）
 * @param description 角色描述（可空）
 * @author Zyaire
 */
public record RoleDtoReq(

        @NotBlank(message = "角色名称不能为空且不超过64个字符")
        @Size(max = 64, message = "角色名称不能为空且不超过64个字符")
        String name,

        @NotBlank(message = "角色编码只能包含大写字母、数字与下划线")
        @Pattern(regexp = "^[A-Z0-9_]{2,32}$", message = "角色编码只能包含大写字母、数字与下划线")
        String code,

        @Size(max = 255, message = "角色描述不能超过255个字符")
        String description) {
}
