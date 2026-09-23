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
 * <p><b>实测踩坑（2026-09-23，A1 加固时被机检抓到）</b>：本表用 {@code @EmbeddedId} 复合主键，
 * {@code saveAll} 的新行<b>不会立即 INSERT</b>，而是挂在持久化上下文里等 flush。
 * 若其后调用的 {@code @Modifying} 批量语句只带 {@code clearAutomatically} 而没有
 * {@code flushAutomatically}，Hibernate 的自动 flush 会按「查询空间」判断——批量语句查的是
 * {@code doc_tag}，与待插入的 {@code doc_document_tag_rel} 无关，于是**不 flush**，
 * 紧接着的 {@code clear()} 会把这批待插入的行直接丢掉：表现为"标签计数 +1 了、关联表却是 0 行"。
 * 因此本工程 19 处 {@code @Modifying} 一律写成
 * {@code clearAutomatically = true, flushAutomatically = true}（先 flush 再执行、执行后清缓存）。</p>
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
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from DocumentTagRel r where r.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /** 删除某标签的全部关系（删除标签时先清理关系）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from DocumentTagRel r where r.tagId = :tagId")
    int deleteByTagId(@Param("tagId") Long tagId);
}
