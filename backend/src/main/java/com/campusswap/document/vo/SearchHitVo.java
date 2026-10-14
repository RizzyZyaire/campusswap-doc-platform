package com.campusswap.document.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 检索命中出参（ES 引擎与 MySQL 降级引擎共用同一结构，见 API 设计 ES-SEARCH-DESIGN §4）。
 *
 * <p><b>高亮字段是"已转义 + 已插 mark 标记"的 HTML 片段</b>：前端用 {@code v-html} 渲染时
 * 只允许 {@code <mark>} 一个标签，其余内容后端已在 {@code SearchTextHighlighter} 里转义，
 * 不存在注入通道。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class SearchHitVo {

    /** 文档 ID（字符串，与全站口径一致）。 */
    private String id;

    /** 标题（纯文本，列表无关键词时展示它）。 */
    private String title;

    /** 标题（已高亮 HTML；无命中时等于转义后的 title）。 */
    private String titleHighlight;

    /** 命中片段（已高亮 HTML；来自正文或摘要）。 */
    private String snippet;

    /** 分类名称。 */
    private String categoryName;

    /** 作者姓名。 */
    private String authorName;

    /** 状态（检索只返回 PUBLISHED，保留字段便于排障）。 */
    private String status;

    /** 发布时间（yyyy-MM-dd HH:mm:ss）。 */
    private String publishAt;

    /**
     * 最后更新时间（yyyy-MM-dd HH:mm:ss）。
     *
     * <p>"最近更新 ↓"排序的展示列就是它：ES 索引里本来就有该字段（keyword，ISO 格式），
     * 不新增查询，只把它一并回给出参。用 String 而不是日期类型——与前端的
     * {@code relativeTime()} 口径一致，也避免后端出现日期类型的序列化差异。</p>
     */
    private String updatedAt;

    /**
     * 相关度分数（ES 引擎给出，保留两位小数的字符串如 {@code "4.83"}）。
     *
     * <p><b>没有相关度时一律为 null，不会出现 {@code "NaN"}</b>：非相关度排序（按发布/更新时间、
     * 阅读量）时 ES 不计算 _score，或本次查询拿不到有效分数，两种情况都给 null
     * （见 {@code DocumentSearchServiceImpl#formatScore}）。MySQL 降级引擎无评分，同样为 null。</p>
     *
     * <p>用字符串而不是浮点类型：项目红线「金额与数值一律整数或定点表示」禁止后端源码里出现
     * 浮点类型关键字（由机检 verify-m6.ps1 的 B8 断言强制，正则扫描含注释），
     * 而相关度本来就只用于展示排序依据，格式化成两位小数即可。</p>
     */
    private String score;

    /** 命中的字段清单（如 ["title","content"]，用于界面提示"命中在标题/正文"）。 */
    private java.util.List<String> matchedFields;
}
