package com.campusswap.document.dto;

import com.campusswap.common.api.PageDtoReq;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 全文检索入参（新增接口 {@code GET /api/search/documents}）。
 *
 * <p>在通用分页入参之上加两样：{@code keyword}（关键词）与 {@code scope}（检索范围，用户要求
 * "搜索前能选择标题 / 内容 / 标题+内容"）。{@code scope} 用 {@code @Pattern} 白名单校验，
 * 非法值直接 400 并给出可选值（与全站枚举参数口径一致）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class DocumentSearchEsDtoReq extends PageDtoReq {

    /** 关键词：必填（空关键词不检索，前端也不会发请求）。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 检索范围：TITLE（只查标题）/ CONTENT（只查正文）/ ALL（标题+摘要+正文，默认）。 */
    @Pattern(regexp = "^(TITLE|CONTENT|ALL)$", message = "检索范围只能是 TITLE / CONTENT / ALL")
    private String scope;

    /** 分类 ID（可选，数字字符串；传父分类 = 含全部子孙分类，与既有检索口径一致）。 */
    @Pattern(regexp = "^\\d{1,19}$", message = "分类ID格式不正确")
    private String categoryId;

    /** 排序方式（DocumentSort，可选；不传时 ES 引擎按相关度 _score 倒序）。 */
    @Pattern(regexp = "^(updatedAt_desc|publishAt_desc|viewCount_desc|relevance)$",
            message = "排序方式只能是 updatedAt_desc / publishAt_desc / viewCount_desc / relevance")
    private String sort;
}
