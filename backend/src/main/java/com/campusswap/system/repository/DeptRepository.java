package com.campusswap.system.repository;

import com.campusswap.entity.Dept;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 部门仓储（sys_dept）：部门树一次查全（1 条 SQL）后在内存按 {@code parent_id} 组树。
 *
 * @author Zyaire
 */
public interface DeptRepository extends JpaRepository<Dept, Long> {

    /** 全量查询并按排序号升序（部门树专用，SQL 预算 1 条）。 */
    List<Dept> findAllByOrderBySortOrderAscIdAsc();

    /** 是否已存在同名部门（同级重名校验）。 */
    boolean existsByParentIdAndName(Long parentId, String name);

    /** 是否存在子部门（删除部门前的前置校验，BR-04）。 */
    boolean existsByParentId(Long parentId);

    /**
     * 取某部门的全部子孙（无递归 SQL）。
     *
     * <p>按<b>完整路径段</b>匹配，不是裸 {@code LIKE '0,1%'} —— 否则 {@code "0,10"} 会被误判成 {@code "0,1"} 的后代。</p>
     *
     * @param path 目标部门的完整路径（= 自身 {@code ancestors} + "," + 自身 {@code id}）
     * @return 子孙部门
     */
    @Query("select d from Dept d where d.ancestors = :path or d.ancestors like concat(:path, ',%')"
            + " order by d.sortOrder asc, d.id asc")
    List<Dept> findByAncestorsPath(@Param("path") String path);
}
