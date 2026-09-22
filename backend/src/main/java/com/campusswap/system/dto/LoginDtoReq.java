package com.campusswap.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录入参（API_SPECIFICATION §4.1.1）。
 *
 * <p>{@code password} <b>不做 trim</b>：首尾空格是密码的一部分。</p>
 *
 * @param username 登录名（工号），4~64 字符
 * @param password 密码，6~64 字符
 * @author Zyaire
 */
public record LoginDtoReq(

        @NotBlank(message = "用户名不能为空，且长度需在4到64之间")
        @Size(min = 4, max = 64, message = "用户名不能为空，且长度需在4到64之间")
        String username,

        @NotBlank(message = "密码不能为空，且长度需在6到64之间")
        @Size(min = 6, max = 64, message = "密码不能为空，且长度需在6到64之间")
        String password) {
}
