package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 回收站查询入参（API_SPECIFICATION §4.6.3）。
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentTrashDtoReq extends PageDtoReq {

    /** 关键词：对 title / summary 模糊匹配。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;
}
