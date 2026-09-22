package com.campusswap.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑标签入参（API_SPECIFICATION §4.8.7，GLOSSARY §3.7 的 {@code TagUpdateDtoReq}）。
 *
 * @param name 标签名称（1~16 字，全局唯一）
 * @author Zyaire
 */
public record TagUpdateDtoReq(

        @NotBlank(message = "标签名称不能为空且不超过16个字符")
        @Size(max = 16, message = "标签名称不能为空且不超过16个字符")
        String name) {
}
