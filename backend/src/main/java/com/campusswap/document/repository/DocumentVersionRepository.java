package com.campusswap.document.repository;

import com.campusswap.entity.DocumentVersion;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档版本仓储（doc_version）：追加型留痕，只增不改；仅「彻底删除」时物理清理。
 *
 * @author Zyaire
 */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    /** 某文档的全部版本，按版本号倒序（最新在前）。 */
    List<DocumentVersion> findByDocumentIdOrderByVersionNumDesc(Long documentId);

    /** 某文档的版本分页（version_num 倒序，最新在前）。 */
    Page<DocumentVersion> findByDocumentIdOrderByVersionNumDesc(Long documentId, Pageable pageable);

    /** 某文档的指定版本（版本对比用，命中唯一键 {@code uk_doc_version}）。 */
    DocumentVersion findByDocumentIdAndVersionNum(Long documentId, Integer versionNum);

    /** 某文档的版本条数。 */
    long countByDocumentId(Long documentId);

    /**
     * 彻底删除文档时清理版本留痕（物理删除）。
     *
     * <p>用原生 SQL 的原因：文档已处于 {@code deleted = 1}，且本表自身也带软删除过滤，
     * 走实体删除会只置 {@code deleted = 1} 而留下垃圾行 —— BR-08 要求的是真正清干净。</p>
     *
     * @param documentId 文档 ID
     * @return 删除行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM doc_version WHERE document_id = :documentId", nativeQuery = true)
    int deleteByDocumentIdPhysically(@Param("documentId") Long documentId);
}
