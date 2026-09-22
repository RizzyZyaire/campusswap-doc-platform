package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 标签列表查询入参（API_SPECIFICATION §4.8.5，GLOSSARY §3.7 的 {@code TagPageDtoReq}）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class TagPageDtoReq extends PageDtoReq {

    /** 关键词：匹配 name。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;
}
