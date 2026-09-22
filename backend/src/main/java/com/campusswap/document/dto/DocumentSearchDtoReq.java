package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 检索文档查询入参（API_SPECIFICATION §4.6.1，GLOSSARY §3.7 的 {@code DocumentSearchDtoReq}）。
 *
 * <p>本接口只查已发布文档：{@code status} 可空或 {@code PUBLISHED}，其它值由 Service 返回 400。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentSearchDtoReq extends PageDtoReq {

    /** 关键词：对 title / summary 模糊匹配。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 分类 ID（传父分类 = 含全部子孙分类）。 */
    @Pattern(regexp = "^\\d{1,19}$", message = "分类ID格式不正确")
    private String categoryId;

    /** 标签 ID 数组（AND 命中：需同时包含全部所选标签）。 */
    private List<String> tagIds;

    /** 状态：仅允许 PUBLISHED（可空）。 */
    private String status;

    /** 起始时间（闭区间，yyyy-MM-dd HH:mm:ss）。 */
    private String startTime;

    /** 结束时间（闭区间）。 */
    private String endTime;

    /** 排序方式（DocumentSort）。 */
    private String sort;
}
