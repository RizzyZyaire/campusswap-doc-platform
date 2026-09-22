package com.campusswap.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审核通过 / 归档入参（API_SPECIFICATION §4.7.2 与 §4.7.4 共用，GLOSSARY §3.7 的 {@code DocumentAuditDtoReq}）。
 *
 * @param remark 审核意见（BR-12 必填，1~255 字）
 * @author Zyaire
 */
public record DocumentAuditDtoReq(

        @NotBlank(message = "审核意见不能为空且不超过255字")
        @Size(max = 255, message = "审核意见不能为空且不超过255字")
        String remark) {
}
