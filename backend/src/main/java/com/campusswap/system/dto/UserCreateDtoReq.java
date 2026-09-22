package com.campusswap.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新增用户入参（API_SPECIFICATION §4.2.2）。
 *
 * <p>注意：{@code roles} 是<b>角色编码</b>数组（如 {@code ["STAFF"]}），不是角色 ID —— 与
 * {@code DeptRoleDtoReq.roleIds} 的区别见 GLOSSARY §3.7 易错点。</p>
 *
 * @param username 登录名（4~64 字符，字母/数字/下划线，全局唯一）
 * @param realName 真实姓名
 * @param deptId   部门 ID（字符串形式）
 * @param roles    角色编码数组，不传 = 仅绑定 STAFF
 * @param phone    手机号（可空）
 * @param email    邮箱（可空）
 * @param password 初始密码（≥8 位且同时含字母与数字）
 * @author Zyaire
 */
public record UserCreateDtoReq(

        @NotBlank(message = "登录名不能为空，且只能包含字母、数字与下划线")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,64}$", message = "登录名不能为空，且只能包含字母、数字与下划线")
        String username,

        @NotBlank(message = "姓名不能为空且不超过64个字符")
        @Size(max = 64, message = "姓名不能为空且不超过64个字符")
        String realName,

        @NotBlank(message = "部门不存在，请重新选择")
        String deptId,

        List<String> roles,

        @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号格式不正确")
        String phone,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "初始密码至少8位且需同时包含字母和数字")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,64}$", message = "初始密码至少8位且需同时包含字母和数字")
        String password) {
}
