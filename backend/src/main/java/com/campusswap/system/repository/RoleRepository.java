package com.campusswap.system.repository;

import com.campusswap.entity.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 角色仓储（sys_role）。
 *
 * <p>角色列表 SQL 预算 3 条：角色分页 1 条 + {@code findRoleIdIn} 批量取授权关系 + 权限名批量取。</p>
 *
 * @author Zyaire
 */
public interface RoleRepository extends JpaRepository<Role, Long>, JpaSpecificationExecutor<Role> {

    /** 按角色编码查询（命中唯一键 {@code uk_sys_role_code}）。 */
    Optional<Role> findByCode(String code);

    /** 角色编码是否已存在。 */
    boolean existsByCode(String code);

    /** 按主键集合批量查询（供"给用户授角色"批量校验角色是否存在）。 */
    List<Role> findAllByIdIn(Collection<Long> ids);

    /** 按角色编码集合批量查询（用户入参 {@code roles} 是编码，需要先映射成 ID）。 */
    List<Role> findByCodeIn(Collection<String> codes);

    /** 某用户的角色编码集合（登录/查询用户信息时用，一条 SQL）。 */
    @Query("select r.code from Role r, UserRole ur where ur.roleId = r.id and ur.userId = :userId"
            + " order by r.sortOrder asc, r.id asc")
    List<String> findCodesByUserId(@Param("userId") Long userId);
}
