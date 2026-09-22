package com.campusswap.document.repository;

import com.campusswap.entity.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 文档分类仓储（doc_category）：分类树一次查全（1 条 SQL）+ 内存组树。
 *
 * @author Zyaire
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 全量查询并按排序号升序（分类树专用，SQL 预算 1 条）。 */
    List<Category> findAllByOrderBySortOrderAscIdAsc();

    /** 同级是否已存在同名分类。 */
    boolean existsByParentIdAndName(Long parentId, String name);

    /** 是否存在子分类（删除分类前的前置校验）。 */
    boolean existsByParentId(Long parentId);
}
