package com.campusswap.system.repository;

import com.campusswap.entity.DeptRole;
import com.campusswap.entity.DeptRoleId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部门-角色关联仓储（sys_dept_role）：部门绑定角色后，部门成员在权限合并时自动继承该角色。
 *
 * @author Zyaire
 */
public interface DeptRoleRepository extends JpaRepository<DeptRole, DeptRoleId> {

    /** 查某部门绑定的全部角色。 */
    List<DeptRole> findByDeptId(Long deptId);

    /** 按部门 ID 集合批量查（部门树回显角色用，SQL 预算 1 条）。 */
    List<DeptRole> findByDeptIdIn(Collection<Long> deptIds);

    /** 查绑定了某角色的全部部门（角色授权变更后按部门批量清缓存用）。 */
    List<DeptRole> findByRoleId(Long roleId);

    /** 该角色被多少部门绑定（删除角色前的前置校验）。 */
    long countByRoleId(Long roleId);

    /** 清空某部门的角色绑定（重新绑定前先清后插）。 */
    @Modifying
    @Transactional
    @Query("delete from DeptRole dr where dr.deptId = :deptId")
    int deleteByDeptId(@Param("deptId") Long deptId);

    /** 删除某角色的全部部门绑定（删除角色时先解绑）。 */
    @Modifying
    @Transactional
    @Query("delete from DeptRole dr where dr.roleId = :roleId")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
