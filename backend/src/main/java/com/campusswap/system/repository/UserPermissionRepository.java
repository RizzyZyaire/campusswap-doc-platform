package com.campusswap.system.repository;

import com.campusswap.entity.UserPermission;
import com.campusswap.entity.UserPermissionId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户直授权限仓储（sys_user_permission）。
 *
 * <p>M3 阶段<b>只读</b>：本平台不提供补权/收权接口（PRD 非目标），该表由 DBA 直接维护，
 * 权限合并算法仍按 {@code ARCHITECTURE §6.2} 把它算进并集。</p>
 *
 * @author Zyaire
 */
public interface UserPermissionRepository extends JpaRepository<UserPermission, UserPermissionId> {

    /** 查某用户的直授权限点 ID。 */
    List<UserPermission> findByUserId(Long userId);

    /** 按用户 ID 集合批量查（用户列表/权限详情用，一条 IN 查询）。 */
    List<UserPermission> findByUserIdIn(Collection<Long> userIds);

    /** 该权限点被多少用户直授（删除权限前的前置校验，BR-03）。 */
    long countByPermissionId(Long permissionId);
}
