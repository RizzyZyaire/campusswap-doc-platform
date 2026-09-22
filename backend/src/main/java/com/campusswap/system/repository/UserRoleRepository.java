package com.campusswap.system.repository;

import com.campusswap.entity.UserRole;
import com.campusswap.entity.UserRoleId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户-角色关联仓储（sys_user_role）：绑定/解绑一律操作本实体，禁走集合的 {@code add/remove}。
 *
 * <p>批量补名接口（如用户列表回显角色）必须用 {@link #findByUserIdIn} 一条 {@code IN} 查询，禁循环单查。</p>
 *
 * @author Zyaire
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    /** 查某用户的全部角色绑定。 */
    List<UserRole> findByUserId(Long userId);

    /** 按用户 ID 集合批量查（用户列表回显角色，SQL 预算 1 条）。 */
    List<UserRole> findByUserIdIn(Collection<Long> userIds);

    /** 查某角色下的全部绑定（角色授权变更后批量清缓存用）。 */
    List<UserRole> findByRoleId(Long roleId);

    /** 是否已绑定该角色（重复授权幂等判断）。 */
    boolean existsByUserIdAndRoleId(Long userId, Long roleId);

    /** 该角色下有多少用户（删除角色前的前置校验）。 */
    long countByRoleId(Long roleId);

    /** 清空某用户的全部角色（重新授权时先清后插）。 */
    @Modifying
    @Transactional
    @Query("delete from UserRole ur where ur.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** 删除某角色的全部绑定（删除角色时先解绑）。 */
    @Modifying
    @Transactional
    @Query("delete from UserRole ur where ur.roleId = :roleId")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
