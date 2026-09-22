package com.campusswap.document.repository;

import com.campusswap.entity.Favorite;
import com.campusswap.entity.FavoriteId;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档收藏仓储（doc_favorite）：复合主键天然去重，重复收藏/取消均幂等。
 *
 * @author Zyaire
 */
public interface FavoriteRepository extends JpaRepository<Favorite, FavoriteId> {

    /** 是否已收藏（文档详情第 4 条 SQL）。 */
    boolean existsByUserIdAndDocumentId(Long userId, Long documentId);

    /** 我的收藏分页（按收藏时间倒序）。 */
    Page<Favorite> findByUserId(Long userId, Pageable pageable);

    /** 我的收藏总数。 */
    long countByUserId(Long userId);

    /** 某文档被收藏数（与 {@code doc_document.favorite_count} 对账用）。 */
    long countByDocumentId(Long documentId);

    /** 取消收藏（幂等：不存在则影响 0 行）。 */
    @Modifying
    @Transactional
    @Query("delete from Favorite f where f.userId = :userId and f.documentId = :documentId")
    int deleteByUserIdAndDocumentId(@Param("userId") Long userId, @Param("documentId") Long documentId);

    /** 收藏用户 ID 清单（文档彻底删除前清理收藏）。 */
    @Query("select f.userId from Favorite f where f.documentId = :documentId")
    List<Long> findUserIdsByDocumentId(@Param("documentId") Long documentId);

    /** 某时间点之前的收藏（预留：清理历史数据用）。 */
    long countByCreatedAtBefore(LocalDateTime time);
}
