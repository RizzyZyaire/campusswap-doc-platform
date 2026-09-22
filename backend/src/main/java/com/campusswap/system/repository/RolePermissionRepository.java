package com.campusswap.system.repository;

import com.campusswap.entity.RolePermission;
import com.campusswap.entity.RolePermissionId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色-权限关联仓储（sys_role_permission）：授权一律「先清空再批量插入」，不做逐条 diff。
 *
 * @author Zyaire
 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

    /** 查某角色的全部权限绑定。 */
    List<RolePermission> findByRoleId(Long roleId);

    /** 按角色 ID 集合批量查（角色列表回显权限数，SQL 预算 1 条）。 */
    List<RolePermission> findByRoleIdIn(Collection<Long> roleIds);

    /** 该权限点被多少角色引用（删除权限点前的前置校验）。 */
    long countByPermissionId(Long permissionId);

    /** 清空某角色的权限（重新授权时先清后插）。 */
    @Modifying
    @Transactional
    @Query("delete from RolePermission rp where rp.roleId = :roleId")
    int deleteByRoleId(@Param("roleId") Long roleId);

    /** 删除某权限点的全部授权（删除权限点时先回收）。 */
    @Modifying
    @Transactional
    @Query("delete from RolePermission rp where rp.permissionId = :permissionId")
    int deleteByPermissionId(@Param("permissionId") Long permissionId);
}
