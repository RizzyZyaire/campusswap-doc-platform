package com.campusswap.system.repository;

import com.campusswap.entity.Permission;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 权限仓储（sys_permission）。
 *
 * <p><b>权限树接口 SQL 预算 = 1 条</b>：{@link #findAllByOrderBySortOrderAscIdAsc()} 一次查全表，
 * 在内存按 {@code parent_id} 组装三层树——严禁按层递归查询（最隐蔽的 N+1，ARCHITECTURE §10.5 铁律二）。</p>
 *
 * @author Zyaire
 */
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    /** 全量查询并按同级排序号升序（权限树专用）。 */
    List<Permission> findAllByOrderBySortOrderAscIdAsc();

    /** 按权限编码查询（命中唯一键 {@code uk_sys_permission_code}）。 */
    Optional<Permission> findByCode(String code);

    /** 权限编码是否已存在。 */
    boolean existsByCode(String code);

    /** 按权限编码集合批量查询（角色授权入参校验用，一条 IN 查询）。 */
    List<Permission> findByCodeIn(Collection<String> codes);

    /** 按主键集合批量查询（角色授权/部门授权回显用）。 */
    List<Permission> findAllByIdIn(Collection<Long> ids);

    /**
     * 取某节点的<b>全部子孙</b>权限（无递归 SQL）。
     *
     * <p><b>实现要点（易错）</b>：不能只写 {@code ancestors LIKE '0,1%'} —— 那会把
     * {@code "0,10"}（根级 10 号节点的子节点）误判成 {@code "0,1"} 的后代。
     * 必须按<b>完整路径段</b>匹配：{@code ancestors = :path OR ancestors LIKE CONCAT(:path, ',%')}。</p>
     *
     * @param path 目标节点的完整路径，= 该节点 {@code ancestors} + "," + 该节点 {@code id}
     * @return 子孙节点（按同级排序号升序）
     */
    @Query("select p from Permission p where p.ancestors = :path or p.ancestors like concat(:path, ',%')"
            + " order by p.sortOrder asc, p.id asc")
    List<Permission> findByAncestorsPath(@Param("path") String path);

    /** 是否存在子节点（删除权限前的前置校验，BR-03）。 */
    boolean existsByParentId(Long parentId);

    /**
     * RBAC 权限合并算法 —— <b>一条 SQL</b>算出用户的全部权限码（ARCHITECTURE §6.2）。
     *
     * <p>合并口径：用户直授角色 ∪ 部门继承角色 → 角色权限，再并上用户直授权限。</p>
     *
     * <p>用原生 SQL 的原因：这是一条三元并集 + 子查询，JPQL 无法表达 {@code UNION}；
     * 原生查询<b>不会</b>自动套用 {@code @SQLRestriction}，所以这里显式写了 {@code p.deleted = 0}。</p>
     *
     * @param userId 用户 ID
     * @return 去重后的权限码集合
     */
    @Query(value = """
            SELECT DISTINCT p.code
              FROM sys_permission p
             WHERE p.deleted = 0
               AND p.id IN (
                   SELECT rp.permission_id
                     FROM sys_role_permission rp
                    WHERE rp.role_id IN (
                        SELECT ur.role_id FROM sys_user_role ur WHERE ur.user_id = :userId
                        UNION
                        SELECT dr.role_id
                          FROM sys_dept_role dr
                         WHERE dr.dept_id = (SELECT u.dept_id FROM sys_user u WHERE u.id = :userId)
                    )
                   UNION
                   SELECT up.permission_id FROM sys_user_permission up WHERE up.user_id = :userId
               )
             ORDER BY p.code
            """, nativeQuery = true)
    List<String> findEffectivePermCodes(@Param("userId") Long userId);
}
