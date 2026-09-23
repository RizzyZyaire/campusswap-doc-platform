package com.campusswap.document.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 全文检索条件（{@code MATCH ... AGAINST ... IN BOOLEAN MODE} 的入参对象）。
 *
 * <p>来源：{@code API_SPECIFICATION §9.3} / {@code UI_UX_SPECIFICATION §10.4}（契约已冻结）。
 * 触发条件是「关键词非空且去空格后长度 ≥ 2」；关键词为空或不足 2 字时仍走原有 Criteria 分支
 * （{@link DocumentListQuery}），既有 SQL 预算不受影响。</p>
 *
 * <p><b>布尔表达式口径</b>（实测 MySQL 8.0.46 + {@code ngram_token_size = 2}）：</p>
 * <ol>
 *   <li>按空白切词 → 每个词剥离布尔符号 {@code + - * " ( ) ~ < > @}；</li>
 *   <li>剥离后为空则丢弃（例如整词就是 {@code **}）；</li>
 *   <li><b>长度 &lt; 2 的词丢弃</b>：ngram 按 2-gram 切分，1 字词进不了索引；
 *       更关键的是它会把整个 AND 表达式拉成 0 命中（实测库内验证：
 *       {@code +M4* +A* +123456*} = 0 行，去掉 1 字的 {@code A} 后 {@code +M4* +123456*} = 1 行）；</li>
 *   <li>其余词逐个拼成 {@code +词*}（前缀匹配），空格相连 → {@code '+国家* +自然科学*'}。</li>
 * </ol>
 *
 * <p><b>为什么符号当分隔符而不是原地删除</b>：原地删除会把 {@code M4-A-001122} 变成
 * {@code M4A001122}，而文档里存的是带连字符的原文（ngram 索引里是 {@code M4} {@code 4-} {@code -A} …
 * 这一串 bigram），实测 {@code +M4A001122*} 命中 0、{@code +M4* +001122*} 命中 1。
 * 校园口径下「2026-09-23」「M4-A-001122」这类关键词很常见，故符号按分隔符处理，
 * 效果仍是「表达式里不再出现任何布尔符号」（§9.3 安全要求）。</p>
 *
 * <p>表达式里只出现 {@code :t0 :t1 …} 占位符，词值一律走绑定参数，不存在字符串拼接注入。</p>
 *
 * @param expression  拼好的布尔表达式（如 {@code '+国家* +自然科学*'}）
 * @param terms       剥离后的词列表（保序，用于高亮与 matchedIn 判定）
 * @param categoryIds 分类 ID 集合（已含子孙；null 或空 = 不限）
 * @param tagIds      标签 ID 列表（AND 命中：需同时包含全部）
 * @param startTime   起始时间（可空，按 created_at 过滤）
 * @param endTime     结束时间（可空）
 * @author Zyaire
 */
public record DocumentFullTextQuery(
        String expression,
        List<String> terms,
        Collection<Long> categoryIds,
        List<Long> tagIds,
        LocalDateTime startTime,
        LocalDateTime endTime) {

    /** 需要剥离的布尔符号（API_SPECIFICATION §9.3 的安全要求）。 */
    private static final String BOOLEAN_SYMBOLS = "+-*\"()~<>@";

    /** ngram 解析器的最小可检索词长（服务端 {@code ngram_token_size = 2}）。 */
    private static final int MIN_TERM_LENGTH = 2;

    /**
     * 判断关键词是否应当走全文检索分支。
     *
     * <p>口径：关键词非空、去空格后长度 ≥ 2，且剥离后至少剩一个可检索词。
     * 不满足时调用方必须回落原有 Criteria 分支（关键词为空时保持既有行为完全不变）。</p>
     *
     * @param keyword 原始关键词（可空）
     * @return true = 走全文检索
     */
    public static boolean supports(String keyword) {
        return keyword != null && keyword.trim().length() >= MIN_TERM_LENGTH && !terms(keyword).isEmpty();
    }

    /**
     * 由关键词与筛选条件组装全文检索条件。
     *
     * @param keyword     原始关键词（调用方须先确认 {@link #supports(String)} 为 true）
     * @param categoryIds 分类 ID 集合（已含子孙；null 或空 = 不限）
     * @param tagIds      标签 ID 列表（AND 命中）
     * @param startTime   起始时间（可空）
     * @param endTime     结束时间（可空）
     * @return 全文检索条件
     */
    public static DocumentFullTextQuery of(String keyword, Collection<Long> categoryIds, List<Long> tagIds,
                                          LocalDateTime startTime, LocalDateTime endTime) {
        List<String> terms = terms(keyword);
        return new DocumentFullTextQuery(expression(terms), terms, categoryIds, tagIds, startTime, endTime);
    }

    /**
     * 切词 + 剥离布尔符号 + 丢弃长度不足的词。
     *
     * @param keyword 原始关键词（可空）
     * @return 可检索词列表（保序、去重）
     */
    public static List<String> terms(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String raw : keyword.trim().split("\\s+")) {
            // 符号按分隔符处理：先把布尔符号替换成空格，再按空白切，避免把 M4-A 拼成 M4A
            for (String piece : raw.replaceAll("[" + java.util.regex.Pattern.quote(BOOLEAN_SYMBOLS) + "]", " ").split("\\s+")) {
                String term = piece.trim();
                if (term.length() < MIN_TERM_LENGTH || terms.contains(term)) {
                    continue;
                }
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    /**
     * 拼布尔表达式：每词 {@code +词*}。
     *
     * @param terms 可检索词列表
     * @return 布尔表达式（无可用词时返回空串）
     */
    public static String expression(List<String> terms) {
        StringBuilder expression = new StringBuilder();
        for (String term : terms) {
            if (!expression.isEmpty()) {
                expression.append(' ');
            }
            expression.append('+').append(term).append('*');
        }
        return expression.toString();
    }
}
