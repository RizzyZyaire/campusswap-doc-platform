package com.campusswap.system.vo;

import java.util.List;

/**
 * 角色已授权限出参（API_SPECIFICATION §4.3.5，GLOSSARY §3.7 的 {@code RolePermissionVo}）。
 *
 * <p>组合型 VO，<b>不</b>继承 {@code AuditVo}（API_SPECIFICATION §2.7.1 铁律 3）。</p>
 *
 * @param roleId        角色 ID（字符串）
 * @param permissionIds 该角色已勾选的权限 ID 集合（含父节点与按钮节点）
 * @author Zyaire
 */
public record RolePermissionVo(String roleId, List<String> permissionIds) {
}
