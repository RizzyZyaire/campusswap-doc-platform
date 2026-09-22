package com.campusswap.system.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 部门绑定角色入参（API_SPECIFICATION §4.5.6，GLOSSARY §3.7 的 {@code DeptRoleDtoReq}）。
 *
 * <p>⚠️ 易错点：这里是<b>角色 ID</b> 数组（对应 {@code sys_role.id}），
 * 而 {@code UserCreateDtoReq.roles} 是<b>角色编码</b>数组（对应 {@code sys_role.code}）。</p>
 *
 * @param roleIds 角色 ID 数组；空数组 = 清空绑定
 * @author Zyaire
 */
public record DeptRoleDtoReq(

        @NotNull(message = "角色不存在，请重新选择")
        List<String> roleIds) {
}
