package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 我的文档查询入参（API_SPECIFICATION §4.6.2）。
 *
 * <p>作者条件由后端强制注入（{@code created_by = 当前用户}），本 DTO 不提供作者参数（防越权）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentMineDtoReq extends PageDtoReq {

    /** 关键词：对 title / summary 模糊匹配。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 状态：仅 DRAFT / PUBLISHED / ARCHIVED；不传 = 全部（不含回收站）。 */
    private String status;
}
