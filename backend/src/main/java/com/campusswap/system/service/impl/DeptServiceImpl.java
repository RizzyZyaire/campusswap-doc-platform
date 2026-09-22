package com.campusswap.system.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TxUtil;
import com.campusswap.entity.Dept;
import com.campusswap.entity.DeptRole;
import com.campusswap.entity.Role;
import com.campusswap.system.dto.DeptCreateDtoReq;
import com.campusswap.system.dto.DeptRoleDtoReq;
import com.campusswap.system.dto.DeptUpdateDtoReq;
import com.campusswap.system.repository.DeptRepository;
import com.campusswap.system.repository.DeptRoleRepository;
import com.campusswap.system.repository.RoleRepository;
import com.campusswap.system.repository.UserRepository;
import com.campusswap.system.service.DeptService;
import com.campusswap.system.service.PermissionCacheService;
import com.campusswap.system.vo.DeptRoleVo;
import com.campusswap.system.vo.DeptVo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部门服务实现。
 *
 * <p>性能口径（ARCHITECTURE §10.5）：部门树预算 <b>1 条 SQL</b>；
 * {@code GET /api/depts/{id}/roles} 预算 <b>2 条</b>（① 部门存在性 + ② 关联表一次查全）。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeptServiceImpl implements DeptService {

    private final DeptRepository deptRepository;
    private final DeptRoleRepository deptRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PermissionCacheService permissionCacheService;

    /**
     * 部门树：一次查全 + 内存组树（1 条 SQL）。
     *
     * @return 根节点数组
     */
    @Override
    @Transactional(readOnly = true)
    public List<DeptVo> tree() {
        List<Dept> all = deptRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, DeptVo> voById = new LinkedHashMap<>();
        for (Dept dept : all) {
            voById.put(dept.getId(), DeptVo.of(dept));
        }
        List<DeptVo> roots = new ArrayList<>();
        for (Dept dept : all) {
            DeptVo vo = voById.get(dept.getId());
            DeptVo parent = dept.getParentId() == null ? null : voById.get(dept.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    /**
     * 新增部门。
     *
     * @param req 新增入参
     * @return 新增后的部门
     */
    @Override
    @Transactional
    public DeptVo create(DeptCreateDtoReq req) {
        Long parentId = IdUtil.toLong(req.parentId(), "上级部门ID");
        Dept parent = requireParent(parentId);
        String name = req.name().trim();
        if (deptRepository.existsByParentIdAndName(parentId, name)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同级部门下已存在同名部门");
        }
        Dept dept = Dept.builder()
                .name(name)
                .parentId(parentId)
                .ancestors(childAncestors(parent))
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        deptRepository.saveAndFlush(dept);
        return DeptVo.of(dept);
    }

    /**
     * 编辑部门：支持移动，事务内级联重写子孙 {@code ancestors}。
     *
     * @param id  部门 ID
     * @param req 编辑入参
     * @return 编辑后的部门
     */
    @Override
    @Transactional
    public DeptVo update(Long id, DeptUpdateDtoReq req) {
        Dept dept = getDeptOrThrow(id);
        Long parentId = IdUtil.toLong(req.parentId(), "上级部门ID");
        Dept parent = requireParent(parentId);
        String name = req.name().trim();
        if (deptRepository.existsByParentIdAndName(parentId, name)
                && !(parentId.equals(dept.getParentId()) && name.equals(dept.getName()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同级部门下已存在同名部门");
        }

        String oldPath = dept.getAncestors() + "," + dept.getId();
        if (parent != null && isSelfOrDescendant(parent, oldPath)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能将部门移动到其子部门下");
        }

        String newPath = childAncestors(parent) + "," + dept.getId();
        dept.setName(name);
        dept.setParentId(parentId);
        dept.setAncestors(childAncestors(parent));
        dept.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        deptRepository.saveAndFlush(dept);

        if (!newPath.equals(oldPath)) {
            List<Dept> descendants = deptRepository.findByAncestorsPath(oldPath);
            for (Dept descendant : descendants) {
                descendant.setAncestors(newPath + descendant.getAncestors().substring(oldPath.length()));
            }
            deptRepository.saveAll(descendants);
            log.info("部门子树移动完成: id={}, 受影响子部门={} 个", id, descendants.size());
        }
        // 部门角色绑定可能随组织调整变化，保守清该部门用户的权限缓存
        TxUtil.afterCommit(() -> permissionCacheService.evictByDeptId(id));
        return DeptVo.of(dept);
    }

    /**
     * 删除部门（有子部门或在职用户时拒绝）。
     *
     * @param id 部门 ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Dept dept = getDeptOrThrow(id);
        if (deptRepository.existsByParentId(id) || userRepository.countByDeptId(id) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "该部门下仍有子部门或员工，请先转移");
        }
        deptRoleRepository.deleteByDeptId(id);
        deptRepository.delete(dept);
        TxUtil.afterCommit(() -> permissionCacheService.evictByDeptId(id));
    }

    /**
     * 查询部门已绑定角色（2 条 SQL：部门存在性 + 关联表）。
     *
     * @param id 部门 ID
     * @return 部门 → 角色 ID 集合
     */
    @Override
    @Transactional(readOnly = true)
    public DeptRoleVo rolesOf(Long id) {
        getDeptOrThrow(id);
        List<String> roleIds = deptRoleRepository.findByDeptId(id).stream()
                .map(relation -> IdUtil.toStr(relation.getRoleId()))
                .toList();
        return new DeptRoleVo(IdUtil.toStr(id), roleIds);
    }

    /**
     * 部门绑定角色：覆盖式保存 + 提交后清该部门用户权限缓存（授权立即生效）。
     *
     * @param id  部门 ID
     * @param req 角色 ID 集合
     */
    @Override
    @Transactional
    public void bindRoles(Long id, DeptRoleDtoReq req) {
        getDeptOrThrow(id);
        List<Long> roleIds = req.roleIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> IdUtil.toLong(value, "角色ID"))
                .distinct()
                .toList();
        if (!roleIds.isEmpty()) {
            List<Role> roles = roleRepository.findAllByIdIn(roleIds);
            if (roles.size() != roleIds.size()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在，请重新选择");
            }
        }
        deptRoleRepository.deleteByDeptId(id);
        if (!roleIds.isEmpty()) {
            List<DeptRole> relations = roleIds.stream().map(roleId -> new DeptRole(id, roleId)).toList();
            deptRoleRepository.saveAll(relations);
        }
        TxUtil.afterCommit(() -> permissionCacheService.evictByDeptId(id));
        log.info("部门绑定角色完成: deptId={}, 角色数={}", id, roleIds.size());
    }

    /**
     * 按 ID 取部门，不存在则 404。
     *
     * @param id 部门 ID
     * @return 部门实体
     */
    @Override
    @Transactional(readOnly = true)
    public Dept getDeptOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部门不存在或已被删除");
        }
        return deptRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "部门不存在或已被删除"));
    }

    /**
     * 取父部门：{@code 0} 表示根（返回 null），其余必须存在否则 400。
     *
     * @param parentId 父部门 ID
     * @return 父部门（根为 null）
     */
    private Dept requireParent(Long parentId) {
        if (parentId == 0L) {
            return null;
        }
        return deptRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "上级部门不存在，请重新选择"));
    }

    /**
     * 计算子部门的 {@code ancestors}。
     *
     * @param parent 父部门（null = 根）
     * @return 祖级路径
     */
    private String childAncestors(Dept parent) {
        return parent == null ? "0" : parent.getAncestors() + "," + parent.getId();
    }

    /**
     * 环检测：目标父部门是否为自身或自身子孙。
     *
     * @param parent  目标父部门
     * @param oldPath 当前部门的完整路径
     * @return true = 会形成环
     */
    private boolean isSelfOrDescendant(Dept parent, String oldPath) {
        String parentPath = parent.getAncestors() + "," + parent.getId();
        return oldPath.equals(parentPath) || parentPath.startsWith(oldPath + ",");
    }
}
