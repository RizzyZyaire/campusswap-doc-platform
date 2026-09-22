package com.campusswap.system.repository;

import com.campusswap.entity.User;
import com.campusswap.entity.enums.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用户仓储（sys_user）。
 *
 * <p>列表查询的 SQL 预算见 {@code ARCHITECTURE §10.5}：用户分页 1 条，部门名 / 角色码由 Service 侧
 * {@code findAllById} 批量补齐，本接口不提供任何"逐个查"的方法。</p>
 *
 * @author Zyaire
 */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /** 按登录账号查询（命中唯一键 {@code uk_sys_user_username}）。 */
    Optional<User> findByUsername(String username);

    /** 登录名是否已存在（新增用户时前置校验，配合 {@code 409 CONFLICT_STATUS}）。 */
    boolean existsByUsername(String username);

    /** 按状态统计（统计接口用）。 */
    long countByStatus(UserStatus status);

    /** 按主键集合批量查询（继承自 {@code JpaRepository#findAllById} 的显式声明，便于阅读 SQL 预算）。 */
    List<User> findAllByIdIn(Collection<Long> ids);

    /** 某部门下的用户数（删除部门前的前置校验，BR-04）。 */
    long countByDeptId(Long deptId);

    /** 某部门下的用户 ID（部门角色变更后清权限缓存用，一条查询）。 */
    @Query("select u.id from User u where u.deptId = :deptId")
    List<Long> findIdsByDeptId(@Param("deptId") Long deptId);

    /** 若干部门下的用户 ID（角色授权变更后批量清缓存用）。 */
    @Query("select u.id from User u where u.deptId in :deptIds")
    List<Long> findIdsByDeptIdIn(@Param("deptIds") Collection<Long> deptIds);
}
