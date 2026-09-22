package com.campusswap.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 重置密码入参（API_SPECIFICATION §4.2.6，管理员替员工重置）。
 *
 * @param newPassword 新密码（≥8 位且同时含字母与数字）
 * @author Zyaire
 */
public record UserPasswordDtoReq(

        @NotBlank(message = "新密码至少8位且需同时包含字母和数字")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,64}$", message = "新密码至少8位且需同时包含字母和数字")
        String newPassword) {
}
