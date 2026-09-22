package com.campusswap.system.dto;

import com.campusswap.entity.enums.PermType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑权限入参（API_SPECIFICATION §4.4.3）。
 *
 * <p>本 record <b>不含 {@code code}</b>：权限编码不可修改，请求体带 {@code code} 会被忽略。</p>
 *
 * @param name      权限名称
 * @param type      权限类型：DIR / MENU / BUTTON
 * @param parentId  父节点 ID（不能是自己或自己的子孙）
 * @param path      前端路由（DIR/MENU 必填）
 * @param icon      图标名（可空）
 * @param sortOrder 同级排序号（≥ 0）
 * @author Zyaire
 */
public record PermissionUpdateDtoReq(

        @NotBlank(message = "权限名称不能为空且不超过64个字符")
        @Size(max = 64, message = "权限名称不能为空且不超过64个字符")
        String name,

        @NotNull(message = "权限类型取值非法")
        PermType type,

        @NotBlank(message = "父节点不存在，请重新选择")
        String parentId,

        @Size(max = 128, message = "目录与菜单必须填写前端路由")
        String path,

        @Size(max = 64, message = "图标名不能超过64个字符")
        String icon,

        @Min(value = 0, message = "排序号必须大于等于0")
        Integer sortOrder) {
}
