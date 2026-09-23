package com.campusswap.document.repository;

/**
 * 全文检索链路的结果行：列表投影行 + 高亮片段 + 命中字段。
 *
 * <p>{@link #row} 与其它列表链路（检索 Criteria 分支、我的文档、回收站、收藏）<b>完全同构</b>，
 * 都是 {@link DocumentListRow}；额外两个字段是 {@code API_SPECIFICATION §9.3} 新增的出参来源。</p>
 *
 * @param row       列表投影行（13 个展示字段，不含 contentMd）
 * @param highlight 命中的正文片段（命中词两侧各 30 字，命中词以 {@code <em>} 包裹；
 *                  正文无命中时为 null）
 * @param matchedIn 命中字段：{@code title} / {@code summary} / {@code content}
 * @author Zyaire
 */
public record DocumentSearchRow(DocumentListRow row, String highlight, String matchedIn) {

    /** 命中字段取值：标题。 */
    public static final String MATCHED_IN_TITLE = "title";
    /** 命中字段取值：摘要。 */
    public static final String MATCHED_IN_SUMMARY = "summary";
    /** 命中字段取值：正文。 */
    public static final String MATCHED_IN_CONTENT = "content";
}
