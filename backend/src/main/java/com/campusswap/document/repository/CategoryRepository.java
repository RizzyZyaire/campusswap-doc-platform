package com.campusswap.document.repository;

import com.campusswap.entity.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 取某分类的全部子孙（无递归 SQL）。
     *
     * <p>按<b>完整路径段</b>匹配（{@code ancestors = :path OR LIKE CONCAT(:path, ',%')}）：
     * 裸前缀 LIKE 在 id 段位复用时会误判（见 ARCHITECTURE §10 规约 19）。</p>
     *
     * @param path 目标分类的完整路径（= 自身 {@code ancestors} + "," + 自身 {@code id}）
     * @return 子孙分类
     */
    @Query("select c from Category c where c.ancestors = :path or c.ancestors like concat(:path, ',%')"
            + " order by c.sortOrder asc, c.id asc")
    List<Category> findByAncestorsPath(@Param("path") String path);
}
