package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档治理列表查询入参（API_SPECIFICATION §9.2，权限 {@code doc:manage}）。
 *
 * <p>与 {@link DocumentSearchDtoReq} 的边界：检索接口只返回已发布文档且权限是 {@code doc:search}；
 * 本接口是<b>全状态</b>治理列表（含回收站行 {@code deleted = 1}），只对 {@code doc:manage} 开放。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentManageDtoReq extends PageDtoReq {

    /**
     * 状态筛选：{@code DRAFT} / {@code PUBLISHED} / {@code ARCHIVED} / {@code TRASH}；空 = 全部。
     *
     * <p>非法取值返回 400「文档状态取值非法」。{@code TRASH} 命中的是 {@code deleted = 1} 的行，
     * 必须走原生 SQL 绕过 {@code @SQLRestriction("deleted = 0")}。</p>
     */
    private String status;

    /** 关键词：走与检索页同一套口径（≥2 字全文检索，能搜正文；不足 2 字回落 title / summary 模糊匹配）。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 分类 ID（传父分类 = 含全部子孙分类）。 */
    @Pattern(regexp = "^\\d{1,19}$", message = "分类ID格式不正确")
    private String categoryId;

    /** 拟稿人 ID（等价 {@code created_by}；本表无「发文单位」列，单位维度的治理筛选不在此接口）。 */
    @Pattern(regexp = "^\\d{1,19}$", message = "拟稿人ID格式不正确")
    private String authorId;

    /** 起始时间（闭区间，按 created_at 过滤，yyyy-MM-dd HH:mm:ss）。 */
    private String startTime;

    /** 结束时间（闭区间）。 */
    private String endTime;

    /** 排序方式（DocumentSort：updatedAt_desc / publishAt_desc / viewCount_desc / relevance）。 */
    private String sort;
}
