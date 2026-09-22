package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 我的收藏查询入参（API_SPECIFICATION §4.6.15，GLOSSARY §3.7 的 {@code FavoritePageDtoReq}）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class FavoritePageDtoReq extends PageDtoReq {

    /** 关键词：对 title / summary 模糊匹配。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;
}
