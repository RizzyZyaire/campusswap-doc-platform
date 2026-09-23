package com.campusswap.document.repository;

import com.campusswap.entity.Document;
import com.campusswap.entity.enums.DocumentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档仓储（doc_document）：普通链路走 JPQL/Criteria，回收站与收藏链路走原生 SQL。
 *
 * <p><b>为什么回收站必须用原生 SQL</b>：删除文档会把 {@code deleted} 置 1（{@code @SQLDelete}），
 * 而 {@code @SQLRestriction("deleted = 0")} 对所有 JPQL/Criteria 查询自动生效 ——
 * 想看回收站里的数据只能绕过它（API_SPECIFICATION §4.6.3 明确要求）。
 * 原生查询不走实体状态机，因此「进回收站 / 恢复 / 彻底删除」也用原生 UPDATE/DELETE，并显式维护 {@code updated_at/by}。</p>
 *
 * <p>列表链路统一 {@code SELECT} {@link DocumentColumns#COLUMNS}，归还 {@code Object[]}，
 * 由 {@link DocumentListRow#of(Object[])} 按下标映射。</p>
 *
 * @author Zyaire
 */
public interface DocumentRepository extends JpaRepository<Document, Long>,
        JpaSpecificationExecutor<Document>, DocumentQueryRepository {

    /**
     * 详情：主表 + 只读关联 {@code category} 一次抓取（单条无分页风险）。
     *
     * <p>会读 {@code content_md} —— 仅详情/编辑/审核链路可调，列表禁止。</p>
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    @Query("select d from Document d left join fetch d.category where d.id = :id")
    Optional<Document> findDetailById(@Param("id") Long id);

    /** 按状态统计（统计接口用）。 */
    long countByStatus(DocumentStatus status);

    /** 按状态集合统计。 */
    long countByStatusIn(Collection<DocumentStatus> statuses);

    /** 某分类下是否还有未删除文档（删除分类前的前置校验，BR-13）。 */
    long countByCategoryId(Long categoryId);

    /** 我的文档计数（不含回收站）。 */
    @Query("select count(d) from Document d where d.createdBy = :userId"
            + " and d.status <> com.campusswap.entity.enums.DocumentStatus.TRASH")
    long countMine(@Param("userId") Long userId);

    /** 阅读量原子自增（Redis 去重后调用，避免丢失更新）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Document d set d.viewCount = d.viewCount + 1 where d.id = :id")
    int increaseViewCount(@Param("id") Long id);

    /** 收藏数原子增减（delta 为 +1 / -1）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Document d set d.favoriteCount = d.favoriteCount + :delta where d.id = :id")
    int updateFavoriteCount(@Param("id") Long id, @Param("delta") int delta);

    /** 派生血缘：某文档派生出的全部文档 ID。 */
    @Query("select d.id from Document d where d.derivedFromId = :derivedFromId")
    List<Long> findIdsByDerivedFromId(@Param("derivedFromId") Long derivedFromId);

    /**
     * 统计概览：三个计数用<b>一条</b>原生 SQL 取回（禁止 3 次单独 count）。
     *
     * <p>返回 {@code List} 而不是单个 {@code Object[]}：Spring Data 会把结果集本身转成数组，
     * 声明成 {@code Object[]} 会拿到「装着行数组的数组」，取下标就错位了。</p>
     *
     * @param userId 当前用户 ID
     * @return 单行 3 列：{我的文档数, 我的收藏数, 平台已发布数}
     */
    @Query(value = "SELECT"
            + " (SELECT COUNT(*) FROM doc_document d WHERE d.deleted = 0 AND d.created_by = :userId"
            + "    AND d.status <> 'TRASH'),"
            + " (SELECT COUNT(*) FROM doc_favorite f JOIN doc_document d2 ON d2.id = f.document_id"
            + "    AND d2.deleted = 0 WHERE f.user_id = :userId),"
            + " (SELECT COUNT(*) FROM doc_document d3 WHERE d3.deleted = 0 AND d3.status = 'PUBLISHED')",
            nativeQuery = true)
    List<Object[]> statOverview(@Param("userId") Long userId);

    /**
     * 回收站分页（原生 SQL 显式查 {@code deleted = 1}）。
     *
     * @param authorId 作者 ID；传 0 表示不限（管理员）
     * @param keyword  关键词；传空串表示不过滤
     * @param pageable 分页
     * @return 分页结果（每行 16 列，见 {@link DocumentColumns#COLUMNS}）
     */
    @Query(value = "SELECT " + DocumentColumns.COLUMNS + " FROM doc_document d"
            + " WHERE d.deleted = 1"
            + " AND (:authorId = 0 OR d.created_by = :authorId)"
            + " AND (:keyword = '' OR d.title LIKE CONCAT('%', :keyword, '%')"
            + "      OR d.summary LIKE CONCAT('%', :keyword, '%'))"
            + " ORDER BY d.updated_at DESC, d.id DESC",
            countQuery = "SELECT COUNT(*) FROM doc_document d"
                    + " WHERE d.deleted = 1"
                    + " AND (:authorId = 0 OR d.created_by = :authorId)"
                    + " AND (:keyword = '' OR d.title LIKE CONCAT('%', :keyword, '%')"
                    + "      OR d.summary LIKE CONCAT('%', :keyword, '%'))",
            nativeQuery = true)
    Page<Object[]> findTrashPage(@Param("authorId") Long authorId,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);

    /**
     * 按 ID 取回收站文档（原生 SQL，绕过软删除过滤）。
     *
     * @param id 文档 ID
     * @return 0 或 1 行
     */
    @Query(value = "SELECT " + DocumentColumns.COLUMNS + " FROM doc_document d"
            + " WHERE d.id = :id AND d.deleted = 1", nativeQuery = true)
    List<Object[]> findTrashById(@Param("id") Long id);

    /**
     * 进回收站：一条 SQL 同时置 {@code status = 'TRASH'}、{@code deleted = 1} 并让版本号 +1。
     *
     * <p>版本号必须在这里一起 +1：删除也要写一条 {@code DELETE} 版本快照（§4.6.9），
     * 而恢复时又会 +1 并写 {@code RESTORE} 快照 —— 如果删除不 +1，恢复写出的版本号会与
     * 删除快照撞 {@code uk_doc_version(document_id, version_num)} 唯一键（M4 实测踩到）。</p>
     *
     * @param id         文档 ID
     * @param operatorId 操作人（写 updated_by）
     * @return 影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE doc_document SET status = 'TRASH', deleted = 1,"
            + " version_num = version_num + 1, updated_at = NOW(), updated_by = :operatorId"
            + " WHERE id = :id AND deleted = 0", nativeQuery = true)
    int moveToTrash(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /**
     * 从回收站恢复：{@code deleted = 0} + 回到草稿 + 版本号 +1。
     *
     * @param id         文档 ID
     * @param operatorId 操作人
     * @return 影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE doc_document SET deleted = 0, status = 'DRAFT', reject_reason = NULL,"
            + " version_num = version_num + 1, updated_at = NOW(), updated_by = :operatorId"
            + " WHERE id = :id AND deleted = 1", nativeQuery = true)
    int restoreFromTrash(@Param("id") Long id, @Param("operatorId") Long operatorId);

    /**
     * 彻底删除主表行（物理删除，BR-08）。
     *
     * @param id 文档 ID
     * @return 影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM doc_document WHERE id = :id AND deleted = 1", nativeQuery = true)
    int destroyPhysically(@Param("id") Long id);

    /**
     * 我的收藏分页：JOIN 关系表并按收藏时间倒序（原生 SQL，一条 SQL 取文档展示列）。
     *
     * @param userId   当前用户 ID
     * @param keyword  关键词；传空串表示不过滤
     * @param pageable 分页
     * @return 分页结果（每行 16 列）
     */
    @Query(value = "SELECT " + DocumentColumns.COLUMNS + " FROM doc_document d"
            + " JOIN doc_favorite f ON f.document_id = d.id"
            + " WHERE d.deleted = 0 AND f.user_id = :userId"
            + " AND (:keyword = '' OR d.title LIKE CONCAT('%', :keyword, '%')"
            + "      OR d.summary LIKE CONCAT('%', :keyword, '%'))"
            + " ORDER BY f.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM doc_document d"
                    + " JOIN doc_favorite f ON f.document_id = d.id"
                    + " WHERE d.deleted = 0 AND f.user_id = :userId"
                    + " AND (:keyword = '' OR d.title LIKE CONCAT('%', :keyword, '%')"
                    + "      OR d.summary LIKE CONCAT('%', :keyword, '%'))",
            nativeQuery = true)
    Page<Object[]> findFavoritePage(@Param("userId") Long userId,
                                    @Param("keyword") String keyword,
                                    Pageable pageable);
}
