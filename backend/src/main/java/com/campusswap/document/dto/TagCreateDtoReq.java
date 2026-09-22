package com.campusswap.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增标签入参（API_SPECIFICATION §4.8.6，GLOSSARY §3.7 的 {@code TagCreateDtoReq}）。
 *
 * @param name 标签名称（1~16 字，全局唯一，BR-14）
 * @author Zyaire
 */
public record TagCreateDtoReq(

        @NotBlank(message = "标签名称不能为空且不超过16个字符")
        @Size(max = 16, message = "标签名称不能为空且不超过16个字符")
        String name) {
}
