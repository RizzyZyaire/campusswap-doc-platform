package com.campusswap.document.repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 文档列表的定制查询片段（Criteria API + DTO 构造器投影）。
 *
 * <p>为什么不用 {@code JpaSpecificationExecutor} 直接返回实体：
 * ① 实体带 {@code content_md}，列表查询会读出大文本（课件 3.1 红线三）；
 * ② {@code Page<Entity>} 配集合型 {@code JOIN FETCH} 会退化成内存分页（{@code HHH000104}）。
 * 所以这里用 Criteria 的 {@code multiselect + construct} 只取展示列，一条 SQL 完成「过滤 + 排序 + 分页」。</p>
 *
 * @author Zyaire
 */
public interface DocumentQueryRepository {

    /**
     * 按动态条件分页查询文档列表（只查展示列，不含正文）。
     *
     * @param query    动态条件
     * @param pageable 分页与排序
     * @return 分页结果（元素为列表投影行）
     */
    Page<DocumentListRow> search(DocumentListQuery query, Pageable pageable);

    /**
     * 全文检索分页（{@code MATCH(title,summary,content_md) AGAINST(:expr IN BOOLEAN MODE)}，原生 SQL）。
     *
     * <p>为什么走原生 SQL：{@code MATCH ... AGAINST} 与 ngram 解析器是 MySQL 方言能力，
     * Criteria API 表达不了；同时只有原生 SQL 才能在服务端用 {@code SUBSTRING} 截出正文片段，
     * 避免把 MEDIUMTEXT 整篇正文带回应用（课件 3.1 红线三）。</p>
     *
     * <p>分页口径与 Criteria 分支一致：末页（返回条数 &lt; pageSize）不跑 count 查询
     * （{@code PageableExecutionUtils}）。排序由 {@code pageable.getSort()} 给出，
     * 属性名 → 原生列名在本接口实现里白名单映射（{@code updatedAt} / {@code publishAt} /
     * {@code viewCount} / {@code relevance}）。</p>
     *
     * @param query    全文检索条件（布尔表达式 + 分类/标签/时间）
     * @param pageable 分页与排序
     * @return 分页结果（元素为「列表投影行 + 高亮 + 命中字段」）
     */
    Page<DocumentSearchRow> searchFullText(DocumentFullTextQuery query, Pageable pageable);

    /**
     * 治理列表分页（全状态含回收站，原生 SQL，API_SPECIFICATION §9.2）。
     *
     * <p>与 {@link #searchFullText} 共用同一套 SQL 拼装与行映射：差别只在
     * ① 不强制 {@code status = 'PUBLISHED'}；② 关键词为空时退回 {@code LIKE title/summary}，
     * 而不是要求必须有布尔表达式。列序与类型转换复用 {@link DocumentColumns#LIST_COLUMNS} +
     * {@link DocumentListRow#of(Object[])}。</p>
     *
     * @param query    治理查询条件
     * @param pageable 分页与排序
     * @return 分页结果（元素为「列表投影行 + 高亮 + 命中字段」）
     */
    Page<DocumentSearchRow> searchManage(DocumentManageQuery query, Pageable pageable);

    /**
     * 取某文档的标签 ID 列表（回收站等原生链路复用，避免与实体加载耦合）。
     *
     * @param documentId 文档 ID
     * @return 标签 ID 列表
     */
    List<Long> findTagIds(Long documentId);
}
