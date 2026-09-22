package com.campusswap.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 编辑用户入参（API_SPECIFICATION §4.2.4）。
 *
 * <p>{@code username} 与密码<b>不可</b>经本接口修改。约定里写明"请求体出现这两项要 400"，
 * 所以这里把它们声明成 {@code @Null} 组件：传了就是校验失败（返回约定的中文提示），
 * <b>而不是</b>打开全局 {@code FAIL_ON_UNKNOWN_PROPERTIES}（那会连其它接口的正常扩展字段一起拒掉）。
 * 业务代码不读取这两个组件，因此即使被绕过也不可能写进库。</p>
 *
 * @param realName 真实姓名
 * @param deptId   部门 ID（字符串形式）
 * @param roles    角色编码数组；传空数组 = 清空自定义角色
 * @param phone    手机号（可空）
 * @param email    邮箱（可空）
 * @param username 仅供校验：本接口不接受该字段，传了即 400
 * @param password 仅供校验：本接口不接受该字段，传了即 400
 * @author Zyaire
 */
public record UserUpdateDtoReq(

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

        @Null(message = "登录名与密码不可通过本接口修改")
        String username,

        @Null(message = "登录名与密码不可通过本接口修改")
        String password) {
}
