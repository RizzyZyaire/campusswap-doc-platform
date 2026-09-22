package com.campusswap.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 驳回文档入参（API_SPECIFICATION §4.7.3，GLOSSARY §3.7 的 {@code DocumentRejectDtoReq}）。
 *
 * @param reason 驳回理由（BR-12 必填，1~255 字，会写入 {@code reject_reason} 回传作者）
 * @author Zyaire
 */
public record DocumentRejectDtoReq(

        @NotBlank(message = "驳回理由不能为空")
        @Size(max = 255, message = "驳回理由不能为空")
        String reason) {
}
