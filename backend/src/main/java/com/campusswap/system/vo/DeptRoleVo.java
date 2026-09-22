package com.campusswap.system.vo;

import java.util.List;

/**
 * 部门已绑定角色出参（API_SPECIFICATION §4.5.5，GLOSSARY §3.7 的 {@code DeptRoleVo}）。
 *
 * <p>组合型 VO，<b>不</b>继承 {@code AuditVo}。</p>
 *
 * @param deptId  部门 ID（字符串）
 * @param roleIds 该部门已绑定角色 ID 集合（<b>ID</b>，不是角色编码）
 * @author Zyaire
 */
public record DeptRoleVo(String deptId, List<String> roleIds) {
}
