package com.campusswap.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 自助修改密码入参（API_SPECIFICATION §9.1，登录即可，仅本人）。
 *
 * <p>与管理员重置 {@link UserPasswordDtoReq} 的边界：本 DTO 多一个 {@code oldPassword}（必须校验旧密码），
 * 新密码规则与 F1-08 一致：<b>8–32 位且同时包含字母与数字</b>。</p>
 *
 * @param oldPassword 原密码（必填，BCrypt 校验）
 * @param newPassword 新密码（8–32 位且同时含字母与数字）
 * @author Zyaire
 */
public record PasswordChangeDtoReq(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码需8到32位且同时包含字母和数字")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,32}$", message = "新密码需8到32位且同时包含字母和数字")
        String newPassword) {
}
