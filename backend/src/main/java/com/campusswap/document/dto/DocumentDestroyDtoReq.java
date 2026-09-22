package com.campusswap.document.dto;

import jakarta.validation.constraints.AssertTrue;

/**
 * 彻底删除入参（API_SPECIFICATION §4.6.11，GLOSSARY §3.7 的 {@code DocumentDestroyDtoReq}）。
 *
 * <p>BR-08 二次确认：{@code confirm} 必须为 {@code true}（缺失或 false 都拒绝）。</p>
 *
 * @param confirm 二次确认标记
 * @author Zyaire
 */
public record DocumentDestroyDtoReq(

        @AssertTrue(message = "彻底删除需要二次确认")
        Boolean confirm) {
}
