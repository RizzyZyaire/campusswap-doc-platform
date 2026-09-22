package com.campusswap.document.repository;

import com.campusswap.entity.Document;
import com.campusswap.entity.enums.DocumentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档仓储（doc_document）。
 *
 * <p>课件 3.1 落地要点：</p>
 * <ul>
 *   <li>继承 {@link JpaSpecificationExecutor}，检索/审核队列/我的文档等<b>动态多条件</b>查询走 Criteria 组合（禁字符串 SQL 拼接）；</li>
 *   <li>列表查询一律走 DTO 构造器投影，<b>不读 {@code content_md}</b>（红线三）；</li>
 *   <li>详情用 {@code @EntityGraph(attributePaths = {"category"})} 一条 SQL 抓完主表 + 分类；</li>
 *   <li>计数型字段（{@code view_count} / {@code favorite_count}）用原子自增，禁"读-改-写"。</li>
 * </ul>
 *
 * @author Zyaire
 */
public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    /**
     * 详情：主表 + 只读关联 {@code category} 一次抓取（单条无分页风险，可用 {@code JOIN FETCH} 语义）。
     * 正文由业务按需使用，本方法会读 {@code content_md} —— 仅详情/编辑接口可调。
     */
    @Query("select d from Document d left join fetch d.category where d.id = :id")
    Optional<Document> findDetailById(@Param("id") Long id);

    /** 按状态统计（统计接口用）。 */
    long countByStatus(DocumentStatus status);

    /** 按状态集合统计（一次查询覆盖多状态，禁多次全表 COUNT 叠加）。 */
    long countByStatusIn(Collection<DocumentStatus> statuses);

    /** 某分类下是否还有文档（删除分类前的前置校验）。 */
    long countByCategoryId(Long categoryId);

    /** 我的文档计数（命中 idx_doc_created_by_updated）。 */
    long countByCreatedBy(Long createdBy);

    /** 我的某状态文档计数（命中 idx_doc_status_updated）。 */
    long countByCreatedByAndStatus(Long createdBy, DocumentStatus status);

    /** 阅读量原子自增（Redis 去重后调用，避免丢失更新）。 */
    @Modifying
    @Transactional
    @Query("update Document d set d.viewCount = d.viewCount + 1 where d.id = :id")
    int increaseViewCount(@Param("id") Long id);

    /** 收藏数原子增减（delta 为 +1 / -1）。 */
    @Modifying
    @Transactional
    @Query("update Document d set d.favoriteCount = d.favoriteCount + :delta where d.id = :id")
    int updateFavoriteCount(@Param("id") Long id, @Param("delta") int delta);

    /** 派生血缘：某文档派生出的全部文档 ID。 */
    @Query("select d.id from Document d where d.derivedFromId = :derivedFromId")
    List<Long> findIdsByDerivedFromId(@Param("derivedFromId") Long derivedFromId);
}
