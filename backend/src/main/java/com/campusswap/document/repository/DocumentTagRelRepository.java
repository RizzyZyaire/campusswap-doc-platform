package com.campusswap.document.repository;

import com.campusswap.entity.DocumentTagRel;
import com.campusswap.entity.DocumentTagRelId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档-标签关联仓储（doc_document_tag_rel）。
 *
 * <p>写入纪律（ARCHITECTURE §10.1 A3）：标签增删<b>只走本仓储</b>（清空重插），
 * 禁止对 {@code Document.tags} 只读集合调用 {@code add/remove}。</p>
 *
 * @author Zyaire
 */
public interface DocumentTagRelRepository extends JpaRepository<DocumentTagRel, DocumentTagRelId> {

    /** 某文档的标签关系。 */
    List<DocumentTagRel> findByDocumentId(Long documentId);

    /** 按文档 ID 集合批量查（文档列表回显标签，SQL 预算 1 条）。 */
    List<DocumentTagRel> findByDocumentIdIn(Collection<Long> documentIds);

    /** 按标签 ID 集合批量查（标签筛选 / 标签详情用）。 */
    List<DocumentTagRel> findByTagIdIn(Collection<Long> tagIds);

    /** 该标签被多少文档引用（删除标签前的前置校验）。 */
    long countByTagId(Long tagId);

    /** 清空某文档的标签（重新打标签前先清空）。 */
    @Modifying
    @Transactional
    @Query("delete from DocumentTagRel r where r.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /** 删除某标签的全部关系（删除标签时先清理关系）。 */
    @Modifying
    @Transactional
    @Query("delete from DocumentTagRel r where r.tagId = :tagId")
    int deleteByTagId(@Param("tagId") Long tagId);
}
