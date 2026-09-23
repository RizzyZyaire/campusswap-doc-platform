package com.campusswap.document.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 治理列表查询条件（{@code GET /api/documents/manage}，API_SPECIFICATION §9.2）。
 *
 * <p>为什么整条链路走原生 SQL：<b>空状态 = 全部（含回收站）</b>，而回收站行的 {@code deleted = 1}
 * 会被 {@code @SQLRestriction("deleted = 0")} 从所有 JPQL/Criteria 查询里滤掉 ——
 * 只有原生 SQL 才看得见（与回收站/我的收藏同一条口径）。</p>
 *
 * <p>状态与软删除的对应关系（状态机保证二者一致，见 {@code DocumentRepository#moveToTrash}）：</p>
 * <ul>
 *   <li>{@code status} 为空 → 不加状态与 {@code deleted} 条件（全状态，含回收站）；</li>
 *   <li>{@code status = TRASH} → {@code deleted = 1 AND status = 'TRASH'}；</li>
 *   <li>其它状态 → {@code deleted = 0 AND status = ?}。</li>
 * </ul>
 *
 * <p>关键词与检索页共用一套口径：{@code expression} 非空时用
 * {@code MATCH(title, summary, content_md) AGAINST(:expr IN BOOLEAN MODE)}（含正文，出参带高亮）；
 * 否则用 {@code likeKeyword} 做 title / summary 的 {@code LIKE}（空串 = 不过滤）。</p>
 *
 * @param status          状态名（空串 = 全部；TRASH = 回收站）
 * @param expression      布尔表达式（空串 = 不走全文检索）
 * @param terms           剥离后的词（高亮与 matchedIn 判定用；LIKE 分支为空列表）
 * @param likeKeyword     LIKE 关键词（空串 = 不过滤）
 * @param categoryIds     分类 ID 集合（已含子孙；null 或空 = 不限）
 * @param authorId        拟稿人 ID（null = 不限）
 * @param startTime       起始时间（可空，按 created_at 过滤）
 * @param endTime         结束时间（可空）
 * @author Zyaire
 */
public record DocumentManageQuery(
        String status,
        String expression,
        List<String> terms,
        String likeKeyword,
        Collection<Long> categoryIds,
        Long authorId,
        LocalDateTime startTime,
        LocalDateTime endTime) {

    /** 空值占位：状态为空串表示「全部状态」。 */
    public static final String ALL_STATUSES = "";

    /**
     * 是否走全文检索分支。
     *
     * @return true = MATCH 分支（出参带 highlight / matchedIn）
     */
    public boolean fullText() {
        return expression != null && !expression.isEmpty();
    }
}
