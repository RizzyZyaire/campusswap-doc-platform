package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 待治理（审核队列）查询入参（API_SPECIFICATION §4.7.1，GLOSSARY §3.7 的 {@code ReviewPageDtoReq}）。
 *
 * <p>管理员可见全部用户的文档；{@code status} 仅 PUBLISHED / ARCHIVED，默认 PUBLISHED。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class ReviewPageDtoReq extends PageDtoReq {

    /** 关键词：对 title / summary 模糊匹配。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 状态：PUBLISHED / ARCHIVED（不传 = PUBLISHED）。 */
    private String status;
}
