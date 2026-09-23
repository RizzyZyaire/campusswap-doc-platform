package com.campusswap.document.repository;

import com.campusswap.entity.Document;
import com.campusswap.entity.DocumentTagRel;
import com.campusswap.entity.enums.DocumentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link DocumentQueryRepository} 的 Criteria + 原生 SQL 实现。
 *
 * <p>两条链路的分工：</p>
 * <ul>
 *   <li><b>Criteria</b>（{@link #search}、{@link #findTagIds}）：走实体，{@code @SQLRestriction("deleted = 0")}
 *       自动生效，因此看不见回收站数据属正常；</li>
 *   <li><b>原生 SQL</b>（{@link #searchFullText}、{@link #searchManage}）：全文检索要用 MySQL 方言的
 *       {@code MATCH ... AGAINST}，治理列表要看见 {@code deleted = 1} 的回收站行 —— 两者都绕不过原生 SQL。
 *       原生链路只取展示列（{@link DocumentColumns#LIST_COLUMNS}）+ 服务端 {@code SUBSTRING} 截出的高亮窗口，
 *       整篇 {@code content_md} 不出库。</li>
 * </ul>
 *
 * @author Zyaire
 */
public class DocumentQueryRepositoryImpl implements DocumentQueryRepository {

    /**
     * 高亮片段里命中词两侧保留的字数（API_SPECIFICATION §9.3：两侧各 30 字）。
     */
    private static final int HIGHLIGHT_CONTEXT = 30;

    /**
     * 正文高亮窗口的长度（字）。
     *
     * <p>= 左侧 30 + 命中词 + 右侧 30；关键词上限 64 字，故 160 足够覆盖，
     * 并且保证从数据库里读出的正文最多 160 字（不是整篇 MEDIUMTEXT）。</p>
     */
    private static final int HIGHLIGHT_WINDOW_LENGTH = 160;

    /** 全文检索行：正文高亮窗口列的下标（前 13 列即 {@link DocumentColumns#LIST_COLUMNS}）。 */
    private static final int COL_HIGHLIGHT_WINDOW = 13;

    /** 全文检索行：命中字段列的下标。 */
    private static final int COL_MATCHED_IN = 14;

    /** 全文检索匹配表达式（列组合必须与全文索引 {@code ft_doc_search} 完全一致，子集 MATCH 会报 ERROR 1191）。 */
    private static final String MATCH_EXPRESSION =
            "MATCH(d.title, d.summary, d.content_md) AGAINST(:expr IN BOOLEAN MODE)";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 动态条件分页查询（一条 SQL 取数据 + 一条 SQL 取总数）。
     *
     * @param query    动态条件
     * @param pageable 分页与排序
     * @return 分页结果
     */
    @Override
    public Page<DocumentListRow> search(DocumentListQuery query, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<DocumentListRow> dataQuery = cb.createQuery(DocumentListRow.class);
        Root<Document> root = dataQuery.from(Document.class);
        dataQuery.select(cb.construct(DocumentListRow.class,
                root.get("id"), root.get("title"), root.get("summary"), root.get("categoryId"),
                root.get("createdBy"), root.get("status"), root.get("versionNum"), root.get("priceCents"),
                root.get("viewCount"), root.get("favoriteCount"),
                root.get("createdAt"), root.get("updatedAt"), root.get("updatedBy")));
        dataQuery.where(predicates(cb, root, dataQuery, query));
        dataQuery.orderBy(orders(cb, root, pageable.getSort()));

        TypedQuery<DocumentListRow> typedQuery = entityManager.createQuery(dataQuery);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<DocumentListRow> content = typedQuery.getResultList();

        // 与 Spring Data 的派生分页保持一致：末页（返回条数 < pageSize 且 offset = 0）不跑 count 查询。
        // 这样本片段与 JpaRepository 的分页行为、SQL 预算口径完全对齐。
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            CriteriaBuilder countBuilder = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> countQuery = countBuilder.createQuery(Long.class);
            Root<Document> countRoot = countQuery.from(Document.class);
            countQuery.select(countBuilder.count(countRoot));
            countQuery.where(predicates(countBuilder, countRoot, countQuery, query));
            return entityManager.createQuery(countQuery).getSingleResult();
        });
    }

    /**
     * 取文档的标签 ID 列表（一条 SQL，顺序与打标签顺序一致）。
     *
     * @param documentId 文档 ID
     * @return 标签 ID 列表
     */
    @Override
    public List<Long> findTagIds(Long documentId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<DocumentTagRel> root = query.from(DocumentTagRel.class);
        query.select(root.get("tagId"))
                .where(cb.equal(root.get("documentId"), documentId))
                .orderBy(cb.asc(root.get("createdAt")));
        return entityManager.createQuery(query).getResultList();
    }

    /**
     * 全文检索分页（原生 SQL + {@code MATCH ... AGAINST ... IN BOOLEAN MODE}）。
     *
     * <p>SQL 形态（条件按需追加，全部走绑定参数）：</p>
     * <pre>
     * SELECT 13 个展示列, SUBSTRING(content_md, LOCATE(命中词)-30, 160), CASE ... END
     *   FROM doc_document d
     *  WHERE d.deleted = 0 AND d.status = 'PUBLISHED'
     *    AND MATCH(d.title, d.summary, d.content_md) AGAINST(:expr IN BOOLEAN MODE)
     *    [AND d.category_id IN (:categoryIds)] [AND EXISTS(标签) …] [AND d.created_at …]
     *  ORDER BY [MATCH(...) AGAINST(...) DESC | d.updated_at DESC] , d.id DESC
     * </pre>
     *
     * <p>三个刻意的设计点：</p>
     * <ol>
     *   <li><b>不查 {@code content_md} 整列</b>：只取 {@code SUBSTRING(...)} 截出的高亮窗口
     *       （30 + 最长词 + 30，见 {@link #HIGHLIGHT_WINDOW_LENGTH}），正文不出库（课件 3.1 红线三）；</li>
     *   <li><b>不用子集 MATCH</b>：{@code MATCH(title, summary)} 会报 ERROR 1191（列组合必须与全文索引一致），
     *       因此「命中标题还是摘要」用 {@code LOCATE} 判定；</li>
     *   <li><b>count 口径一致</b>：{@link PageableExecutionUtils#getPage} —— 末页返回条数不足 pageSize 时不发 count。</li>
     * </ol>
     *
     * @param query    全文检索条件
     * @param pageable 分页与排序
     * @return 分页结果
     */
    @Override
    public Page<DocumentSearchRow> searchFullText(DocumentFullTextQuery query, Pageable pageable) {
        List<String> terms = query.terms();
        // 条件用列表 + AND 连接（无 "1 = 1" 占位、无字符串 SQL 拼接，条件为空即跳过）
        List<String> conditions = new ArrayList<>();
        conditions.add("d.deleted = 0");
        conditions.add("d.status = 'PUBLISHED'");
        conditions.add(MATCH_EXPRESSION);
        addCategoryCondition(conditions, query.categoryIds());
        addTagConditions(conditions, query.tagIds());
        addTimeConditions(conditions, query.startTime(), query.endTime());
        String where = whereClause(conditions);

        // 注意：数据查询与 count 查询的参数集不同（count 的 SELECT 里没有 LOCATE(:tN)），
        // 因此两个绑定器分开传，绝不能把 :tN 绑到 count 查询上（Hibernate 抛 UnknownParameterException）
        Consumer<Query> filters = target -> bindFilters(target, query.categoryIds(), query.tagIds(),
                query.startTime(), query.endTime());
        return executeNativePage(dataSql(where, terms, true, MATCH_EXPRESSION, pageable.getSort()),
                countSql(where), pageable, terms,
                target -> {
                    target.setParameter("expr", query.expression());
                    bindTerms(target, terms);
                    filters.accept(target);
                },
                target -> {
                    target.setParameter("expr", query.expression());
                    filters.accept(target);
                });
    }

    /**
     * 治理列表分页（全状态含回收站，原生 SQL + 复用检索链路，API_SPECIFICATION §9.2）。
     *
     * <p>与 {@link #searchFullText} 的两点差别：① 状态条件由 {@link DocumentManageQuery#status()} 决定
     * （空 = 全状态，因此<b>没有</b> {@code deleted = 0} 的固定条件）；② 关键词为空时退回
     * {@code LIKE title/summary}，此时不返回高亮（没有命中词可截）。</p>
     *
     * @param query    治理查询条件
     * @param pageable 分页与排序
     * @return 分页结果
     */
    @Override
    public Page<DocumentSearchRow> searchManage(DocumentManageQuery query, Pageable pageable) {
        List<String> terms = query.fullText() ? query.terms() : List.of();
        boolean trashStatus = DocumentStatus.TRASH.name().equals(query.status());
        List<String> conditions = new ArrayList<>();
        // 状态 + 软删除：空 = 全部（含回收站行）；TRASH = 回收站；其余 = 未删除。
        // TRASH 判定写成「status = 'TRASH' 或 deleted = 1」：状态机（moveToTrash）两者同时置位，
        // 而种子数据只能置其一（种子里的 TRASH 行 deleted 仍是 0），治理列表必须两种都能看见。
        if (trashStatus) {
            conditions.add("(d.status = 'TRASH' OR d.deleted = 1)");
        } else if (StringUtils.hasText(query.status())) {
            conditions.add("d.deleted = 0");
            conditions.add("d.status = :status");
        }
        if (query.fullText()) {
            conditions.add(MATCH_EXPRESSION);
        } else if (StringUtils.hasText(query.likeKeyword())) {
            conditions.add("(d.title LIKE CONCAT('%', :keyword, '%')"
                    + " OR d.summary LIKE CONCAT('%', :keyword, '%'))");
        }
        addCategoryCondition(conditions, query.categoryIds());
        if (query.authorId() != null) {
            conditions.add("d.created_by = :authorId");
        }
        addTimeConditions(conditions, query.startTime(), query.endTime());
        String where = whereClause(conditions);

        Consumer<Query> filters = target -> {
            if (StringUtils.hasText(query.status()) && !trashStatus) {
                target.setParameter("status", query.status());
            }
            if (query.authorId() != null) {
                target.setParameter("authorId", query.authorId());
            }
            bindFilters(target, query.categoryIds(), null, query.startTime(), query.endTime());
        };
        // 关键词参数只在数据查询里出现（count 的 SELECT 没有 LOCATE(:tN)），故两个绑定器分开
        return executeNativePage(dataSql(where, terms, query.fullText(), MATCH_EXPRESSION, pageable.getSort()),
                countSql(where), pageable, terms,
                target -> {
                    bindManageKeyword(target, query);
                    filters.accept(target);
                },
                target -> {
                    if (query.fullText()) {
                        target.setParameter("expr", query.expression());
                    } else if (StringUtils.hasText(query.likeKeyword())) {
                        target.setParameter("keyword", query.likeKeyword());
                    }
                    filters.accept(target);
                });
    }

    /**
     * 绑定治理列表关键词参数（全文分支绑 :expr 与 :tN，LIKE 分支绑 :keyword）。
     *
     * @param target 数据查询
     * @param query  治理查询条件
     */
    private void bindManageKeyword(Query target, DocumentManageQuery query) {
        if (query.fullText()) {
            target.setParameter("expr", query.expression());
            bindTerms(target, query.terms());
        } else if (StringUtils.hasText(query.likeKeyword())) {
            target.setParameter("keyword", query.likeKeyword());
        }
    }

    /**
     * 条件列表 → WHERE 子句（空列表返回空串，绝不拼 {@code 1 = 1} 之类占位条件）。
     *
     * @param conditions 条件片段（常量片段或占位符，词值一律走绑定参数）
     * @return WHERE 子句（含前导空格）或空串
     */
    private String whereClause(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * 组装数据查询 SQL（展示列 + 高亮窗口 + 命中字段 + 条件 + 排序）。
     *
     * @param where           WHERE 子句（含前导空格，可为空串）
     * @param terms           可检索词（为空时不取正文窗口）
     * @param withHighlight   是否取正文高亮窗口
     * @param matchExpression 相关度表达式（{@code sort=relevance} 时用）
     * @param sort            排序规则
     * @return 数据查询 SQL
     */
    private String dataSql(String where, List<String> terms, boolean withHighlight,
                           String matchExpression, Sort sort) {
        StringBuilder sql = new StringBuilder("SELECT ").append(DocumentColumns.LIST_COLUMNS);
        if (withHighlight && !terms.isEmpty()) {
            sql.append(", SUBSTRING(d.content_md, GREATEST(1, ").append(locateExpression(terms, "d.content_md"))
                    .append(" - ").append(HIGHLIGHT_CONTEXT).append("), ").append(HIGHLIGHT_WINDOW_LENGTH).append(')')
                    .append(", CASE WHEN ").append(hitExpression(terms, "d.title"))
                    .append(" THEN '").append(DocumentSearchRow.MATCHED_IN_TITLE)
                    .append("' WHEN ").append(hitExpression(terms, "d.summary"))
                    .append(" THEN '").append(DocumentSearchRow.MATCHED_IN_SUMMARY)
                    .append("' ELSE '").append(DocumentSearchRow.MATCHED_IN_CONTENT).append("' END");
        } else {
            // 非全文分支：不读正文，高亮与命中字段恒为 NULL（保持列数一致，行映射无需分支）
            sql.append(", NULL, NULL");
        }
        return sql.append(" FROM doc_document d").append(where)
                .append(orderBy(sort, withHighlight ? matchExpression : null)).toString();
    }

    /**
     * 组装 count 查询 SQL（与数据查询同一套 WHERE，保证 total 口径一致）。
     *
     * @param where WHERE 子句（含前导空格，可为空串）
     * @return count 查询 SQL
     */
    private String countSql(String where) {
        return "SELECT COUNT(*) FROM doc_document d" + where;
    }

    /**
     * 执行原生分页（数据 + 末页跳过的 count），并把行映射成检索结果行。
     *
     * @param dataSql     数据查询 SQL
     * @param countSql    count 查询 SQL
     * @param pageable    分页
     * @param terms       可检索词（高亮用）
     * @param dataBinder  数据查询的参数绑定器
     * @param countBinder count 查询的参数绑定器（参数集与数据查询不同，必须分开）
     * @return 分页结果
     */
    private Page<DocumentSearchRow> executeNativePage(String dataSql, String countSql, Pageable pageable,
                                                     List<String> terms, Consumer<Query> dataBinder,
                                                     Consumer<Query> countBinder) {
        Query dataQuery = entityManager.createNativeQuery(dataSql);
        dataBinder.accept(dataQuery);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());
        List<?> rawRows = dataQuery.getResultList();
        List<DocumentSearchRow> content = new ArrayList<>(rawRows.size());
        for (Object raw : rawRows) {
            content.add(toSearchRow((Object[]) raw, terms));
        }
        Query countQuery = entityManager.createNativeQuery(countSql);
        countBinder.accept(countQuery);
        // 与搜索结果页一致：末页（返回条数 < pageSize）不跑 count
        return PageableExecutionUtils.getPage(content, pageable,
                () -> ((Number) countQuery.getSingleResult()).longValue());
    }

    /**
     * 追加分类过滤条件（已含子孙的 ID 集合）。
     *
     * @param conditions  条件列表
     * @param categoryIds 分类 ID 集合
     */
    private void addCategoryCondition(List<String> conditions, java.util.Collection<Long> categoryIds) {
        if (categoryIds != null && !categoryIds.isEmpty()) {
            conditions.add("d.category_id IN (:categoryIds)");
        }
    }

    /**
     * 追加标签过滤条件（AND 命中：每个标签一个 EXISTS 子查询，仍是同一条 SQL）。
     *
     * @param conditions 条件列表
     * @param tagIds     标签 ID 列表
     */
    private void addTagConditions(List<String> conditions, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < tagIds.size(); i++) {
            conditions.add("EXISTS (SELECT 1 FROM doc_document_tag_rel r" + i
                    + " WHERE r" + i + ".document_id = d.id AND r" + i + ".tag_id = :tag" + i + ')');
        }
    }

    /**
     * 追加时间区间过滤条件（闭区间，按 {@code created_at}，与 Criteria 分支一致）。
     *
     * @param conditions 条件列表
     * @param startTime  起始时间（可空）
     * @param endTime    结束时间（可空）
     */
    private void addTimeConditions(List<String> conditions, java.time.LocalDateTime startTime,
                                   java.time.LocalDateTime endTime) {
        if (startTime != null) {
            conditions.add("d.created_at >= :startTime");
        }
        if (endTime != null) {
            conditions.add("d.created_at <= :endTime");
        }
    }

    /**
     * 绑定可检索词参数。
     *
     * @param target 原生查询
     * @param terms  可检索词
     */
    private void bindTerms(Query target, List<String> terms) {
        for (int i = 0; i < terms.size(); i++) {
            target.setParameter("t" + i, terms.get(i));
        }
    }

    /**
     * 绑定分类 / 标签 / 时间参数（数据与 count 查询共用）。
     *
     * @param target      原生查询
     * @param categoryIds 分类 ID 集合
     * @param tagIds      标签 ID 列表（可空）
     * @param startTime   起始时间（可空）
     * @param endTime     结束时间（可空）
     */
    private void bindFilters(Query target, java.util.Collection<Long> categoryIds, List<Long> tagIds,
                             java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        if (categoryIds != null && !categoryIds.isEmpty()) {
            target.setParameter("categoryIds", categoryIds);
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            for (int i = 0; i < tagIds.size(); i++) {
                target.setParameter("tag" + i, tagIds.get(i));
            }
        }
        if (startTime != null) {
            target.setParameter("startTime", startTime);
        }
        if (endTime != null) {
            target.setParameter("endTime", endTime);
        }
    }

    /**
     * 原生结果行 → 检索结果行（前 13 列按 {@link DocumentColumns#LIST_COLUMNS} 映射）。
     *
     * @param row   原生结果行
     * @param terms 可检索词（用于生成高亮）
     * @return 检索结果行
     */
    private DocumentSearchRow toSearchRow(Object[] row, List<String> terms) {
        return new DocumentSearchRow(
                DocumentListRow.of(row),
                highlight(DocumentColumns.str(row, COL_HIGHLIGHT_WINDOW), terms),
                DocumentColumns.str(row, COL_MATCHED_IN));
    }

    /**
     * 生成高亮片段：命中词两侧各 30 字，命中词以 {@code <em>} 包裹。
     *
     * <p>窗口由 MySQL 侧截取（起点 = 命中词位置 - 30），这里只需在窗口内定位命中词；
     * 词序与 SQL 里 {@code COALESCE(NULLIF(LOCATE(...)))} 的优先级一致，
     * 因此 Java 侧选中的词与 SQL 选窗口用的词必然是同一个。
     * 英文大小写由 MySQL 的 {@code utf8mb4_unicode_ci} 排序规则负责，Java 侧同样忽略大小写。</p>
     *
     * @param window 正文窗口（可空）
     * @param terms  可检索词
     * @return 高亮片段；正文无命中时返回 null
     */
    private String highlight(String window, List<String> terms) {
        if (window == null || window.isEmpty()) {
            return null;
        }
        String lowerWindow = window.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            int position = lowerWindow.indexOf(term.toLowerCase(Locale.ROOT));
            if (position < 0) {
                continue;
            }
            int start = Math.max(0, position - HIGHLIGHT_CONTEXT);
            int end = Math.min(window.length(), position + term.length() + HIGHLIGHT_CONTEXT);
            return window.substring(start, position)
                    + "<em>" + window.substring(position, position + term.length()) + "</em>"
                    + window.substring(position + term.length(), end);
        }
        return null;
    }

    /**
     * 组装 {@code LOCATE} 链：取「第一个出现在该列里的词」的位置（全不命中返回 NULL）。
     *
     * @param terms  可检索词
     * @param column 列表达式
     * @return SQL 表达式
     */
    private String locateExpression(List<String> terms, String column) {
        StringBuilder expression = new StringBuilder("COALESCE(");
        for (int i = 0; i < terms.size(); i++) {
            expression.append("NULLIF(LOCATE(:t").append(i).append(", ").append(column).append("), 0), ");
        }
        return expression.append("NULL)").toString();
    }

    /**
     * 组装「该列是否命中任一词」的布尔表达式。
     *
     * @param terms  可检索词
     * @param column 列表达式
     * @return SQL 表达式
     */
    private String hitExpression(List<String> terms, String column) {
        StringBuilder expression = new StringBuilder("(");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                expression.append(" OR ");
            }
            expression.append("LOCATE(:t").append(i).append(", ").append(column).append(") > 0");
        }
        return expression.append(')').toString();
    }

    /**
     * 组装排序子句（属性名白名单映射到原生列名，禁止把入参直接拼进 SQL）。
     *
     * @param sort            排序规则
     * @param matchExpression 相关度表达式（{@code sort} 含 {@code relevance} 时使用；非全文分支传 null）
     * @return {@code ORDER BY ...} 子句
     */
    private String orderBy(Sort sort, String matchExpression) {
        StringBuilder order = new StringBuilder();
        if (sort != null && sort.isSorted()) {
            for (Sort.Order item : sort) {
                String column = switch (item.getProperty()) {
                    case "relevance" -> matchExpression;
                    case "publishAt" -> "d.publish_at";
                    case "viewCount" -> "d.view_count";
                    case "updatedAt" -> "d.updated_at";
                    case "id" -> "d.id";
                    default -> null;
                };
                if (column == null) {
                    continue;
                }
                if (!order.isEmpty()) {
                    order.append(", ");
                }
                order.append(column).append(item.isAscending() ? " ASC" : " DESC");
            }
        }
        if (order.isEmpty()) {
            // 默认与 Criteria 分支一致：更新时间倒序 + id 倒序（稳定排序）
            return " ORDER BY d.updated_at DESC, d.id DESC";
        }
        if (!order.toString().contains("d.id")) {
            order.append(", d.id DESC");
        }
        return " ORDER BY " + order;
    }

    /**
     * 组装动态谓词。
     *
     * @param cb    条件构造器
     * @param root  文档根
     * @param query 承载子查询的查询对象
     * @param spec  查询条件
     * @return 谓词数组
     */
    private Predicate[] predicates(CriteriaBuilder cb, Root<Document> root,
                                   CriteriaQuery<?> query, DocumentListQuery spec) {
        List<Predicate> predicates = new ArrayList<>();
        if (StringUtils.hasText(spec.keyword())) {
            String like = "%" + spec.keyword().trim() + "%";
            predicates.add(cb.or(cb.like(root.get("title"), like), cb.like(root.get("summary"), like)));
        }
        if (spec.statuses() != null && !spec.statuses().isEmpty()) {
            predicates.add(root.get("status").in(spec.statuses()));
        }
        if (spec.authorId() != null) {
            predicates.add(cb.equal(root.get("createdBy"), spec.authorId()));
        }
        if (spec.categoryIds() != null && !spec.categoryIds().isEmpty()) {
            predicates.add(root.get("categoryId").in(spec.categoryIds()));
        }
        if (spec.tagIds() != null && !spec.tagIds().isEmpty()) {
            // AND 命中：每个标签一个 IN 子查询，合起来仍是同一条 SQL
            for (Long tagId : spec.tagIds()) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<DocumentTagRel> relRoot = subquery.from(DocumentTagRel.class);
                subquery.select(relRoot.get("documentId")).where(cb.equal(relRoot.get("tagId"), tagId));
                predicates.add(root.get("id").in(subquery));
            }
        }
        if (spec.startTime() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), spec.startTime()));
        }
        if (spec.endTime() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), spec.endTime()));
        }
        return predicates.toArray(new Predicate[0]);
    }

    /**
     * 组装排序（属性名来自 Service 侧已经白名单化的 Sort）。
     *
     * @param cb   条件构造器
     * @param root 文档根
     * @param sort 排序规则
     * @return 排序数组
     */
    private List<Order> orders(CriteriaBuilder cb, Root<Document> root, Sort sort) {
        List<Order> orders = new ArrayList<>();
        if (sort == null || sort.isUnsorted()) {
            orders.add(cb.desc(root.get("updatedAt")));
            orders.add(cb.desc(root.get("id")));
            return orders;
        }
        for (Sort.Order order : sort) {
            orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty())) : cb.desc(root.get(order.getProperty())));
        }
        return orders;
    }

    /**
     * 便于其它查询复用的状态集合：非回收站（回收站在原生链路里单独处理）。
     *
     * @return 非回收站状态
     */
    public static List<DocumentStatus> visibleStatuses() {
        return List.of(DocumentStatus.DRAFT, DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED);
    }
}
