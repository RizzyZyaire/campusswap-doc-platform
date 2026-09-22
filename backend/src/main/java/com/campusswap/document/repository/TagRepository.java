package com.campusswap.document.repository;

import com.campusswap.entity.Tag;
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
 * 文档标签仓储（doc_tag）：扁平字典，{@code use_count} 为冗余计数。
 *
 * @author Zyaire
 */
public interface TagRepository extends JpaRepository<Tag, Long>, JpaSpecificationExecutor<Tag> {

    /** 按名称查询（命中唯一键 {@code uk_doc_tag_name}，打标签时复用已有标签）。 */
    Optional<Tag> findByName(String name);

    /** 按名称集合批量查询（一次 IN 查询复用已有标签，禁循环单查）。 */
    List<Tag> findByNameIn(Collection<String> names);

    /** 按主键集合批量查询（标签列表回显用）。 */
    List<Tag> findAllByIdIn(Collection<Long> ids);

    /** 标签字典列表，按被引用次数倒序。 */
    List<Tag> findAllByOrderByUseCountDescIdAsc();

    /**
     * 取某文档的标签（一条 SQL 关联查询，替代「查关系表 + 批量查标签」两步）。
     *
     * @param documentId 文档 ID
     * @return 标签列表（按打标签顺序）
     */
    @Query("select t from Tag t, DocumentTagRel r where r.tagId = t.id and r.documentId = :documentId"
            + " order by r.createdAt asc, t.id asc")
    List<Tag> findTagsByDocumentId(@Param("documentId") Long documentId);

    /** 标签是否已被引用（删除标签前的前置校验）。 */
    boolean existsByName(String name);

    /** 被引用次数原子增减（打/取消标签时调用）。 */
    @Modifying
    @Transactional
    @Query("update Tag t set t.useCount = t.useCount + :delta where t.id = :id")
    int updateUseCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 批量 +1（重新打标签时对新增的标签一次 UPDATE，避免逐条更新）。
     *
     * @param ids 标签 ID 集合
     * @return 影响行数
     */
    @Modifying
    @Transactional
    @Query("update Tag t set t.useCount = t.useCount + 1 where t.id in :ids")
    int increaseUseCount(@Param("ids") Collection<Long> ids);

    /**
     * 批量 -1（取消标签时一次 UPDATE；数据库层保证不小于 0 由业务侧控制）。
     *
     * @param ids 标签 ID 集合
     * @return 影响行数
     */
    @Modifying
    @Transactional
    @Query("update Tag t set t.useCount = case when t.useCount > 0 then t.useCount - 1 else 0 end"
            + " where t.id in :ids")
    int decreaseUseCount(@Param("ids") Collection<Long> ids);
}
