package com.campusswap.document.repository;

import com.campusswap.entity.Tag;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档标签仓储（doc_tag）：扁平字典，{@code use_count} 为冗余计数。
 *
 * @author Zyaire
 */
public interface TagRepository extends JpaRepository<Tag, Long> {

    /** 按名称查询（命中唯一键 {@code uk_doc_tag_name}，打标签时复用已有标签）。 */
    Optional<Tag> findByName(String name);

    /** 按名称集合批量查询（一次 IN 查询复用已有标签，禁循环单查）。 */
    List<Tag> findByNameIn(Collection<String> names);

    /** 按主键集合批量查询（标签列表回显用）。 */
    List<Tag> findAllByIdIn(Collection<Long> ids);

    /** 标签字典列表，按被引用次数倒序。 */
    List<Tag> findAllByOrderByUseCountDescIdAsc();

    /** 标签是否已被引用（删除标签前的前置校验）。 */
    boolean existsByName(String name);

    /** 被引用次数原子增减（打/取消标签时调用）。 */
    @Modifying
    @Transactional
    @Query("update Tag t set t.useCount = t.useCount + :delta where t.id = :id")
    int updateUseCount(@Param("id") Long id, @Param("delta") int delta);
}
