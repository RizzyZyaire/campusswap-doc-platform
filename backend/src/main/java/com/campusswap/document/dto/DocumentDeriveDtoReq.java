package com.campusswap.document.dto;

import jakarta.validation.constraints.Size;

/**
 * 派生文档入参（API_SPECIFICATION §4.6.8，GLOSSARY §3.7 的 {@code DocumentDeriveDtoReq}）。
 *
 * @param title 新文档标题（可空；不传 = 「源文档标题（副本）」）
 * @author Zyaire
 */
public record DocumentDeriveDtoReq(

        @Size(max = 128, message = "文档标题不能为空且不超过128字")
        String title) {
}
