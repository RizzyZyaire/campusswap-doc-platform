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

    /** 关键词：可空；为空时接口返回空结果并提示，前端通常不会发请求。 */
    @Size(max = 64, message = "关键词长度不能超过64个字符")
    private String keyword;

    /** 检索范围：TITLE（只查标题）/ CONTENT（只查正文）/ ALL（标题+摘要+正文，默认）。 */
    @Pattern(regexp = "^(TITLE|CONTENT|ALL)$", message = "检索范围只能是 TITLE / CONTENT / ALL")
    private String scope;

    /** 分类 ID（可选，数字字符串；传父分类 = 含全部子孙分类，与既有检索口径一致）。 */
    @Pattern(regexp = "^\\d{1,19}$", message = "分类ID格式不正确")
    private String categoryId;

    /** 起始时间（闭区间；支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，可空）——组合查询条件之一。 */
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?$",
            message = "起始时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss")
    private String startTime;

    /** 结束时间（闭区间，格式同起始时间）。 */
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}:\\d{2})?$",
            message = "结束时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss")
    private String endTime;

    /** 标签名（精确匹配，可空）——与关键词/分类/时间范围组合使用。 */
    @Size(max = 32, message = "标签长度不能超过32个字符")
    private String tag;

    /** 排序方式（DocumentSort，可选；不传时 ES 引擎按相关度 _score 倒序）。 */
    @Pattern(regexp = "^(updatedAt_desc|publishAt_desc|viewCount_desc|relevance)$",
            message = "排序方式只能是 updatedAt_desc / publishAt_desc / viewCount_desc / relevance")
    private String sort;
}
