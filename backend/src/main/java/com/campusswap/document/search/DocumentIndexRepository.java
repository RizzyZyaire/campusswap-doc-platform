package com.campusswap.document.search;

import com.campusswap.entity.Document;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 构建检索索引时从**主库**读取数据的仓储（只读，不写库）。
 *
 * <p>为什么用原生 SQL 而不是走实体图：索引重建要一次拿齐"标题 + 摘要 + 正文 + 分类名 +
 * 作者名 + 部门名 + 时间 + 浏览量"。走 JPA 关联会触发 N+1（课件 3.1 红线二），而一次
 * 原生 JOIN 就能拿全；本项目既有的 {@code DocumentQueryRepositoryImpl} 也是这个路子。</p>
 *
 * <p>游标分页（{@code id > :afterId}）而不是 {@code OFFSET}：两万篇文档量下
 * {@code OFFSET} 会越翻越慢（课件 3.1 红线五"禁深分页"）。</p>
 *
 * @author Zyaire
 */
public interface DocumentIndexRepository extends Repository<Document, Long> {

    /**
     * 取一批"已发布且未删除"的文档（按 id 游标推进）。
     *
     * @param afterId 上一批的最后一个 id（首批传 0）
     * @param limit   本批条数
     * @return 索引数据行
     */
    @Query(value = """
            SELECT d.id                AS id,
                   d.title             AS title,
                   d.summary           AS summary,
                   d.content_md        AS contentMd,
                   d.category_id       AS categoryId,
                   c.name              AS categoryName,
                   d.status            AS status,
                   d.created_by        AS authorId,
                   u.real_name         AS authorName,
                   dept.name           AS deptName,
                   d.publish_at        AS publishAt,
                   d.updated_at        AS updatedAt,
                   d.view_count        AS viewCount
              FROM doc_document d
              LEFT JOIN doc_category c    ON c.id = d.category_id AND c.deleted = 0
              LEFT JOIN sys_user u        ON u.id = d.created_by
              LEFT JOIN sys_dept dept     ON dept.id = u.dept_id AND dept.deleted = 0
             WHERE d.status = 'PUBLISHED' AND d.deleted = 0 AND d.id > :afterId
             ORDER BY d.id
             LIMIT :limit
            """, nativeQuery = true)
    List<DocumentIndexRow> findPublishedAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * 取单篇"已发布且未删除"的文档（增量同步用；查不到说明该文档不该进索引）。
     *
     * @param id 文档 ID
     * @return 索引数据行，不存在时为空
     */
    @Query(value = """
            SELECT d.id                AS id,
                   d.title             AS title,
                   d.summary           AS summary,
                   d.content_md        AS contentMd,
                   d.category_id       AS categoryId,
                   c.name              AS categoryName,
                   d.status            AS status,
                   d.created_by        AS authorId,
                   u.real_name         AS authorName,
                   dept.name           AS deptName,
                   d.publish_at        AS publishAt,
                   d.updated_at        AS updatedAt,
                   d.view_count        AS viewCount
              FROM doc_document d
              LEFT JOIN doc_category c    ON c.id = d.category_id AND c.deleted = 0
              LEFT JOIN sys_user u        ON u.id = d.created_by
              LEFT JOIN sys_dept dept     ON dept.id = u.dept_id AND dept.deleted = 0
             WHERE d.id = :id AND d.status = 'PUBLISHED' AND d.deleted = 0
            """, nativeQuery = true)
    DocumentIndexRow findPublishedById(@Param("id") Long id);

    /**
     * 一次取全量"文档 → 标签名"关系（重建时按文档分组打标签，避免逐篇查库）。
     *
     * @return 文档 ID 与标签名
     */
    @Query(value = """
            SELECT r.document_id AS documentId, t.name AS tagName
              FROM doc_document_tag_rel r
              JOIN doc_tag t ON t.id = r.tag_id AND t.deleted = 0
            """, nativeQuery = true)
    List<DocumentTagRow> findAllDocumentTags();

    /**
     * 索引数据行（原生查询投影；别名必须与 getter 同名）。
     *
     * @author Zyaire
     */
    interface DocumentIndexRow {

        /** 文档 ID。 */
        Long getId();

        /** 标题。 */
        String getTitle();

        /** 摘要。 */
        String getSummary();

        /** 正文 Markdown 原文。 */
        String getContentMd();

        /** 分类 ID。 */
        Long getCategoryId();

        /** 分类名（可为空 = 未分类）。 */
        String getCategoryName();

        /** 状态。 */
        String getStatus();

        /** 作者 ID。 */
        Long getAuthorId();

        /** 作者姓名。 */
        String getAuthorName();

        /** 部门名。 */
        String getDeptName();

        /** 发布时间。 */
        LocalDateTime getPublishAt();

        /** 更新时间。 */
        LocalDateTime getUpdatedAt();

        /** 浏览量。 */
        Integer getViewCount();
    }

    /**
     * 文档-标签行。
     *
     * @author Zyaire
     */
    interface DocumentTagRow {

        /** 文档 ID。 */
        Long getDocumentId();

        /** 标签名。 */
        String getTagName();
    }
}
