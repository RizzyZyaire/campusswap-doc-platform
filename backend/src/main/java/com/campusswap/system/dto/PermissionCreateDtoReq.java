package com.campusswap.system.dto;

import com.campusswap.entity.enums.PermType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增权限入参（API_SPECIFICATION §4.4.2）。
 *
 * <p>{@code ancestors} 由后端按父节点自动计算（父的 {@code ancestors} + "," + 父 {@code id}），入参不含该字段。</p>
 *
 * @param name      权限名称
 * @param code      权限编码（2~64 位小写字母/数字/冒号，全局唯一）
 * @param type      权限类型：DIR / MENU / BUTTON
 * @param parentId  父节点 ID（根节点传 "0"）
 * @param path      前端路由（DIR/MENU 必填）
 * @param icon      图标名（可空）
 * @param sortOrder 同级排序号（≥ 0，默认 0）
 * @author Zyaire
 */
public record PermissionCreateDtoReq(

        @NotBlank(message = "权限名称不能为空且不超过64个字符")
        @Size(max = 64, message = "权限名称不能为空且不超过64个字符")
        String name,

        @NotBlank(message = "权限编码只能包含小写字母、数字与冒号")
        @Pattern(regexp = "^[a-z0-9:]{2,64}$", message = "权限编码只能包含小写字母、数字与冒号")
        String code,

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
