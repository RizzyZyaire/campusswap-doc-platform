package com.campusswap.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.security.SecurityContext;
import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TxUtil;
import com.campusswap.entity.Dept;
import com.campusswap.entity.Role;
import com.campusswap.entity.User;
import com.campusswap.entity.UserRole;
import com.campusswap.entity.enums.RoleCode;
import com.campusswap.entity.enums.UserStatus;
import com.campusswap.system.dto.UserCreateDtoReq;
import com.campusswap.system.dto.UserPageDtoReq;
import com.campusswap.system.dto.UserPasswordDtoReq;
import com.campusswap.system.dto.UserStatusDtoReq;
import com.campusswap.system.dto.UserUpdateDtoReq;
import com.campusswap.system.repository.DeptRepository;
import com.campusswap.system.repository.RoleRepository;
import com.campusswap.system.repository.UserRepository;
import com.campusswap.system.repository.UserRoleRepository;
import com.campusswap.system.repository.UserSpecifications;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.TokenService;
import com.campusswap.system.service.UserService;
import com.campusswap.system.vo.UserInfoVo;
import com.campusswap.system.vo.UserVo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户服务实现。
 *
 * <p>性能口径（ARCHITECTURE §10.5）：用户列表预算 <b>4 条 SQL</b> ——
 * ① 用户分页 ② 部门名批量 ③ {@code sys_user_role} 批量 ④ {@code sys_role} 批量。
 * 全部用 {@code IN} + 内存 Map 补齐，<b>绝不</b>在循环里查库。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** BCrypt 强度（BR-20）。 */
    private static final int BCRYPT_STRENGTH = 10;

    private final UserRepository userRepository;
    private final DeptRepository deptRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PermissionCacheService permissionCacheService;
    private final TokenService tokenService;

    /**
     * 用户分页列表：4 条 SQL 常数预算。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<UserVo> page(UserPageDtoReq req) {
        Long deptId = IdUtil.toLongOrNull(req.getDeptId(), "部门ID");
        Page<User> page = userRepository.findAll(
                UserSpecifications.of(req.getKeyword(), req.getStatus(), deptId),
                req.toPageable(Sort.by(Sort.Direction.DESC, "updatedAt", "id")));

        List<User> users = page.getContent();
        Map<Long, String> deptNames = deptNames(users);
        Map<Long, List<String>> roleCodes = roleCodesOfUsers(users);

        List<UserVo> list = users.stream()
                .map(user -> UserVo.of(user, deptNames.get(user.getDeptId()),
                        roleCodes.getOrDefault(user.getId(), List.of())))
                .toList();
        return PageVo.of(list, page.getTotalElements(), page.getNumber() + 1, page.getSize());
    }

    /**
     * 新增用户。
     *
     * @param req 新增入参
     * @return 新增后的用户
     */
    @Override
    @Transactional
    public UserVo create(UserCreateDtoReq req) {
        String username = req.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "登录名已存在，请更换");
        }
        Long deptId = IdUtil.toLong(req.deptId(), "部门ID");
        Dept dept = deptRepository.findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "部门不存在，请重新选择"));
        List<Role> roles = resolveRoles(req.roles(), true);

        User user = User.builder()
                .username(username)
                .passwordHash(BCrypt.hashpw(req.password(), BCrypt.gensalt(BCRYPT_STRENGTH)))
                .realName(req.realName().trim())
                .deptId(deptId)
                .email(blankToNull(req.email()))
                .phone(blankToNull(req.phone()))
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.saveAndFlush(user);
        bindRoles(user.getId(), roles);

        return UserVo.of(user, dept.getName(), roles.stream().map(Role::getCode).toList());
    }

    /**
     * 用户详情。
     *
     * @param id 用户 ID
     * @return 用户出参
     */
    @Override
    @Transactional(readOnly = true)
    public UserVo detail(Long id) {
        User user = getUserOrThrow(id);
        String deptName = deptRepository.findById(user.getDeptId()).map(Dept::getName).orElse(null);
        return UserVo.of(user, deptName, roleCodesOf(id));
    }

    /**
     * 编辑用户：部门或角色变化时，提交后清该用户权限缓存（BR-18）。
     *
     * @param id  用户 ID
     * @param req 编辑入参
     * @return 编辑后的用户
     */
    @Override
    @Transactional
    public UserVo update(Long id, UserUpdateDtoReq req) {
        User user = getUserOrThrow(id);
        Long deptId = IdUtil.toLong(req.deptId(), "部门ID");
        Dept dept = deptRepository.findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "部门不存在，请重新选择"));

        boolean deptChanged = !deptId.equals(user.getDeptId());
        user.setRealName(req.realName().trim());
        user.setDeptId(deptId);
        user.setEmail(blankToNull(req.email()));
        user.setPhone(blankToNull(req.phone()));
        userRepository.saveAndFlush(user);

        boolean rolesChanged = false;
        List<String> codes = roleCodesOf(id);
        if (req.roles() != null) {
            List<Role> roles = resolveRoles(req.roles(), false);
            bindRoles(id, roles);
            codes = roles.stream().map(Role::getCode).toList();
            rolesChanged = true;
        }

        if (deptChanged || rolesChanged) {
            TxUtil.afterCommit(() -> permissionCacheService.evict(id));
        }
        return UserVo.of(user, dept.getName(), codes);
    }

    /**
     * 停用/启用用户：非 ACTIVE 时提交后强制下线并清权限缓存。
     *
     * @param id  用户 ID
     * @param req 状态入参
     * @return 变更后的用户
     */
    @Override
    @Transactional
    public UserVo updateStatus(Long id, UserStatusDtoReq req) {
        if (id.equals(SecurityContext.currentUserId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能修改自己的账号状态");
        }
        User user = getUserOrThrow(id);
        user.setStatus(req.status());
        userRepository.saveAndFlush(user);

        if (req.status() != UserStatus.ACTIVE) {
            TxUtil.afterCommit(() -> {
                tokenService.revokeAll(id);
                permissionCacheService.evict(id);
            });
        }
        String deptName = deptRepository.findById(user.getDeptId()).map(Dept::getName).orElse(null);
        return UserVo.of(user, deptName, roleCodesOf(id));
    }

    /**
     * 重置密码：提交后使该用户全部 token 失效（须用新密码重新登录）。
     *
     * @param id  用户 ID
     * @param req 新密码入参
     */
    @Override
    @Transactional
    public void resetPassword(Long id, UserPasswordDtoReq req) {
        User user = getUserOrThrow(id);
        user.setPasswordHash(BCrypt.hashpw(req.newPassword(), BCrypt.gensalt(BCRYPT_STRENGTH)));
        userRepository.saveAndFlush(user);
        TxUtil.afterCommit(() -> tokenService.revokeAll(id));
    }

    /**
     * 按 ID 取用户，不存在（或已逻辑删除）则 404。
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    @Override
    @Transactional(readOnly = true)
    public User getUserOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在或已被删除");
        }
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在或已被删除"));
    }

    /**
     * 更新最后登录时间。
     *
     * @param userId 用户 ID
     */
    @Override
    @Transactional
    public void touchLastLogin(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    /**
     * 组装登录用户信息（部门名 + 角色编码 + 权限码）。
     *
     * @param user 用户实体
     * @return 登录用户信息
     */
    @Override
    @Transactional(readOnly = true)
    public UserInfoVo buildUserInfo(User user) {
        String deptName = deptRepository.findById(user.getDeptId()).map(Dept::getName).orElse(null);
        List<String> roles = roleCodesOf(user.getId());
        List<String> permissions = permissionCacheService.permissionsOf(user.getId());
        return UserInfoVo.of(user, deptName, roles, permissions);
    }

    /**
     * 取用户的角色编码集合。
     *
     * @param userId 用户 ID
     * @return 角色编码集合
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> roleCodesOf(Long userId) {
        return roleRepository.findCodesByUserId(userId);
    }

    /**
     * 批量取用户姓名（一条 IN 查询）。
     *
     * @param userIds 用户 ID 集合
     * @return 用户 ID → 姓名
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> realNamesOf(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            names.put(user.getId(), user.getRealName());
        }
        return names;
    }

    /**
     * 取单个用户姓名。
     *
     * @param userId 用户 ID
     * @return 姓名；用户不存在时返回 null
     */
    @Override
    @Transactional(readOnly = true)
    public String realNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getRealName).orElse(null);
    }

    /**
     * 批量取部门名（一条 {@code IN} 查询）。
     *
     * @param users 当前页用户
     * @return 部门 ID → 名称
     */
    private Map<Long, String> deptNames(List<User> users) {
        Set<Long> deptIds = new LinkedHashSet<>();
        for (User user : users) {
            if (user.getDeptId() != null) {
                deptIds.add(user.getDeptId());
            }
        }
        if (deptIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Dept dept : deptRepository.findAllById(deptIds)) {
            names.put(dept.getId(), dept.getName());
        }
        return names;
    }

    /**
     * 批量取角色编码（两条 {@code IN} 查询：先关联表，再角色表）。
     *
     * @param users 当前页用户
     * @return 用户 ID → 角色编码列表
     */
    private Map<Long, List<String>> roleCodesOfUsers(List<User> users) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (User user : users) {
            if (user.getId() != null) {
                userIds.add(user.getId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRole> relations = userRoleRepository.findByUserIdIn(userIds);
        Set<Long> roleIds = new LinkedHashSet<>();
        for (UserRole relation : relations) {
            roleIds.add(relation.getRoleId());
        }
        Map<Long, String> codeById = new HashMap<>();
        if (!roleIds.isEmpty()) {
            for (Role role : roleRepository.findAllById(roleIds)) {
                codeById.put(role.getId(), role.getCode());
            }
        }
        Map<Long, List<String>> result = new HashMap<>();
        for (UserRole relation : relations) {
            String code = codeById.get(relation.getRoleId());
            if (code != null) {
                result.computeIfAbsent(relation.getUserId(), key -> new ArrayList<>()).add(code);
            }
        }
        return result;
    }

    /**
     * 角色编码 → 角色实体（编码不存在即 400）。
     *
     * @param codes           角色编码数组
     * @param defaultStaff    为 true 且未传角色时，默认绑定 STAFF
     * @return 角色实体列表
     */
    private List<Role> resolveRoles(List<String> codes, boolean defaultStaff) {
        if (codes == null) {
            if (!defaultStaff) {
                return List.of();
            }
            return roleRepository.findByCode(RoleCode.STAFF.name()).map(List::of).orElse(List.of());
        }
        List<String> distinct = codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<Role> roles = roleRepository.findByCodeIn(distinct);
        if (roles.size() != distinct.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码不存在，请重新选择");
        }
        return roles;
    }

    /**
     * 覆盖式绑定用户角色（先删后插，同事务）。
     *
     * @param userId 用户 ID
     * @param roles  角色实体列表
     */
    private void bindRoles(Long userId, List<Role> roles) {
        userRoleRepository.deleteByUserId(userId);
        if (roles.isEmpty()) {
            return;
        }
        List<UserRole> relations = roles.stream().map(role -> new UserRole(userId, role.getId())).toList();
        userRoleRepository.saveAll(relations);
    }

    /**
     * 空串归一成 null（避免把 {@code ""} 写进可空列）。
     *
     * @param value 原值
     * @return 归一后的值
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
