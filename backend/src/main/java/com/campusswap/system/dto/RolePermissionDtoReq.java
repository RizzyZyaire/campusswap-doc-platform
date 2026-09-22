package com.campusswap.system.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 角色授权入参（API_SPECIFICATION §4.3.6，GLOSSARY §3.7 的 {@code RolePermissionDtoReq}）。
 *
 * <p>覆盖式保存：空数组 = 清空该角色全部权限；传入父节点不会自动补齐子节点。</p>
 *
 * @param permissionIds 权限点 <b>ID</b> 数组（不是权限编码）
 * @author Zyaire
 */
public record RolePermissionDtoReq(

        @NotNull(message = "权限节点不存在，请刷新后重试")
        List<String> permissionIds) {
}
