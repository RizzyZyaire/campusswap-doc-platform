package com.campusswap.system.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.api.PageVo;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TxUtil;
import com.campusswap.entity.Permission;
import com.campusswap.entity.Role;
import com.campusswap.entity.RolePermission;
import com.campusswap.system.dto.RoleDtoReq;
import com.campusswap.system.dto.RolePageDtoReq;
import com.campusswap.system.dto.RolePermissionDtoReq;
import com.campusswap.system.repository.PermissionRepository;
import com.campusswap.system.repository.RolePermissionRepository;
import com.campusswap.system.repository.RoleRepository;
import com.campusswap.system.repository.RoleSpecifications;
import com.campusswap.system.repository.DeptRoleRepository;
import com.campusswap.system.repository.UserRoleRepository;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.service.RoleService;
import com.campusswap.system.vo.RolePermissionVo;
import com.campusswap.system.vo.RoleVo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色服务实现。
 *
 * <p>性能口径（ARCHITECTURE §10.5）：角色列表预算 <b>1 条 SQL</b> ——
 * 列表 VO 不含权限规模（API_SPECIFICATION §4.3.1 明确「角色权限规模不在列表接口返回」），
 * 需要权限集合时走 {@code GET /api/roles/{id}/permissions}（2 条 SQL）。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final DeptRoleRepository deptRoleRepository;
    private final PermissionCacheService permissionCacheService;

    /**
     * 角色分页列表。
     *
     * @param req 查询条件
     * @return 分页结果
     */
    @Override
    @Transactional(readOnly = true)
    public PageVo<RoleVo> page(RolePageDtoReq req) {
        Page<Role> page = roleRepository.findAll(
                RoleSpecifications.of(req.getKeyword()),
                req.toPageable(Sort.by(Sort.Order.desc("isBuiltin"), Sort.Order.asc("sortOrder"),
                        Sort.Order.asc("id"))));
        List<RoleVo> list = page.getContent().stream().map(RoleVo::of).toList();
        return PageVo.of(list, page.getTotalElements(), page.getNumber() + 1, page.getSize());
    }

    /**
     * 新增角色。
     *
     * @param req 角色入参
     * @return 新增后的角色
     */
    @Override
    @Transactional
    public RoleVo create(RoleDtoReq req) {
        String code = req.code().trim();
        if (roleRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "角色编码已存在，请更换");
        }
        Role role = Role.builder()
                .name(req.name().trim())
                .code(code)
                .description(req.description())
                .isBuiltin(0)
                .sortOrder(0)
                .build();
        roleRepository.saveAndFlush(role);
        return RoleVo.of(role);
    }

    /**
     * 编辑角色。
     *
     * @param id  角色 ID
     * @param req 角色入参
     * @return 编辑后的角色
     */
    @Override
    @Transactional
    public RoleVo update(Long id, RoleDtoReq req) {
        Role role = getRoleOrThrow(id);
        if (!role.getCode().equals(req.code().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码不可修改");
        }
        if (Integer.valueOf(1).equals(role.getIsBuiltin()) && !role.getName().equals(req.name().trim())) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "内置角色名称不可修改");
        }
        role.setName(req.name().trim());
        role.setDescription(req.description());
        roleRepository.saveAndFlush(role);

        // 授权口径可能变化（描述/名称虽然不影响权限码），保守起见清该角色下所有用户的权限缓存
        TxUtil.afterCommit(() -> permissionCacheService.evictByRoleId(id));
        return RoleVo.of(role);
    }

    /**
     * 删除角色：内置角色、被用户或被部门引用时一律拒绝。
     *
     * @param id 角色 ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Role role = getRoleOrThrow(id);
        if (Integer.valueOf(1).equals(role.getIsBuiltin())) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "内置角色不可删除");
        }
        if (userRoleRepository.countByRoleId(id) > 0 || deptRoleRepository.countByRoleId(id) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "该角色仍被用户或部门使用，请先解除绑定");
        }
        rolePermissionRepository.deleteByRoleId(id);
        roleRepository.delete(role);
        TxUtil.afterCommit(() -> permissionCacheService.evictByRoleId(id));
    }

    /**
     * 查询角色已授权限。
     *
     * @param id 角色 ID
     * @return 角色 → 权限 ID 集合
     */
    @Override
    @Transactional(readOnly = true)
    public RolePermissionVo permissionsOf(Long id) {
        getRoleOrThrow(id);
        List<String> permissionIds = rolePermissionRepository.findByRoleId(id).stream()
                .map(relation -> IdUtil.toStr(relation.getPermissionId()))
                .toList();
        return new RolePermissionVo(IdUtil.toStr(id), permissionIds);
    }

    /**
     * 角色授权：覆盖式保存 + 提交后清缓存（授权立即生效）。
     *
     * @param id  角色 ID
     * @param req 权限 ID 集合
     */
    @Override
    @Transactional
    public void grantPermissions(Long id, RolePermissionDtoReq req) {
        getRoleOrThrow(id);
        List<Long> permissionIds = req.permissionIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> IdUtil.toLong(value, "权限ID"))
                .distinct()
                .toList();
        if (!permissionIds.isEmpty()) {
            List<Permission> permissions = permissionRepository.findAllByIdIn(permissionIds);
            if (permissions.size() != permissionIds.size()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "权限节点不存在，请刷新后重试");
            }
        }
        rolePermissionRepository.deleteByRoleId(id);
        if (!permissionIds.isEmpty()) {
            List<RolePermission> relations = permissionIds.stream()
                    .map(permissionId -> new RolePermission(id, permissionId))
                    .toList();
            rolePermissionRepository.saveAll(relations);
        }
        TxUtil.afterCommit(() -> permissionCacheService.evictByRoleId(id));
        log.info("角色授权完成: roleId={}, 权限数={}", id, permissionIds.size());
    }

    /**
     * 按 ID 取角色，不存在则 404。
     *
     * @param id 角色 ID
     * @return 角色实体
     */
    @Override
    @Transactional(readOnly = true)
    public Role getRoleOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在或已被删除");
        }
        return roleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在或已被删除"));
    }
}
