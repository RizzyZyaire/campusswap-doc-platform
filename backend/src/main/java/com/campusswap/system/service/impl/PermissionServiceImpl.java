package com.campusswap.system.service.impl;

import com.campusswap.common.api.ErrorCode;
import com.campusswap.common.exception.BusinessException;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.Permission;
import com.campusswap.entity.enums.PermType;
import com.campusswap.system.dto.PermissionCreateDtoReq;
import com.campusswap.system.dto.PermissionUpdateDtoReq;
import com.campusswap.system.repository.PermissionRepository;
import com.campusswap.system.repository.RolePermissionRepository;
import com.campusswap.system.repository.UserPermissionRepository;
import com.campusswap.system.service.PermissionService;
import com.campusswap.system.vo.PermissionVo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 权限服务实现。
 *
 * <p>性能口径（ARCHITECTURE §10.5）：权限树预算 <b>1 条 SQL</b> ——
 * {@code findAll} 一次查全后再内存按 {@code parent_id} 组树，<b>严禁</b>按层递归查询
 * （树形结构最容易被忽略的隐藏 N+1）。</p>
 *
 * <p>层级口径（PRD §3.1）：目录的上级只能是根，菜单的上级必须是目录，按钮的上级必须是菜单。</p>
 *
 * @author Zyaire
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private static final String HIERARCHY_MESSAGE =
            "权限层级不合法：目录的上级只能是根，菜单的上级必须是目录，按钮的上级必须是菜单";

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissionRepository userPermissionRepository;

    /**
     * 权限树：一次查全 + 内存组树（1 条 SQL）。
     *
     * @return 根节点数组
     */
    @Override
    @Transactional(readOnly = true)
    public List<PermissionVo> tree() {
        List<Permission> all = permissionRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, PermissionVo> voById = new LinkedHashMap<>();
        for (Permission permission : all) {
            voById.put(permission.getId(), PermissionVo.of(permission));
        }
        List<PermissionVo> roots = new ArrayList<>();
        for (Permission permission : all) {
            PermissionVo vo = voById.get(permission.getId());
            Long parentId = permission.getParentId();
            PermissionVo parent = parentId == null ? null : voById.get(parentId);
            if (parent == null) {
                roots.add(vo);
            } else {
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    /**
     * 新增权限节点。
     *
     * @param req 新增入参
     * @return 新增后的节点
     */
    @Override
    @Transactional
    public PermissionVo create(PermissionCreateDtoReq req) {
        Long parentId = IdUtil.toLong(req.parentId(), "父节点ID");
        Permission parent = requireParent(parentId);
        assertHierarchy(req.type(), parent);
        assertPath(req.type(), req.path());
        String code = req.code().trim();
        if (permissionRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS, "权限编码已存在，请更换");
        }

        Permission permission = Permission.builder()
                .name(req.name().trim())
                .code(code)
                .type(req.type())
                .parentId(parentId)
                .ancestors(childAncestors(parent))
                .path(req.path())
                .icon(req.icon())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build();
        permissionRepository.saveAndFlush(permission);
        return PermissionVo.of(permission);
    }

    /**
     * 编辑权限节点：支持子树移动，事务内级联重写子孙的 {@code ancestors}。
     *
     * @param id  权限 ID
     * @param req 编辑入参
     * @return 编辑后的节点
     */
    @Override
    @Transactional
    public PermissionVo update(Long id, PermissionUpdateDtoReq req) {
        Permission node = getPermissionOrThrow(id);
        Long parentId = IdUtil.toLong(req.parentId(), "父节点ID");
        Permission parent = requireParent(parentId);

        // 环检测必须放在层级/路径校验之前（2026-09-25 M6 代码审查修正）：
        // 把节点移到自己的子孙下时，层级规则会先报「权限层级不合法」，把真正的原因
        // 盖掉，也与 US-08 / AC-08.3 约定的提示「不能将节点移动到其子节点下」不符。
        // 只是把提示顺序前移：原来会拒绝的形状仍然拒绝（都是 400），不会放宽任何校验。
        String oldPath = node.getAncestors() + "," + node.getId();
        if (parent != null && isSelfOrDescendant(parent, oldPath)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能将节点移动到其子节点下");
        }

        assertHierarchy(req.type(), parent);
        assertPath(req.type(), req.path());

        String newPath = childAncestors(parent) + "," + node.getId();
        node.setName(req.name().trim());
        node.setType(req.type());
        node.setParentId(parentId);
        node.setAncestors(childAncestors(parent));
        node.setPath(req.path());
        node.setIcon(req.icon());
        node.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        permissionRepository.saveAndFlush(node);

        if (!newPath.equals(oldPath)) {
            List<Permission> descendants = permissionRepository.findByAncestorsPath(oldPath);
            for (Permission descendant : descendants) {
                descendant.setAncestors(newPath + descendant.getAncestors().substring(oldPath.length()));
            }
            permissionRepository.saveAll(descendants);
            log.info("权限子树移动完成: id={}, 受影响子孙={} 个", id, descendants.size());
        }
        return PermissionVo.of(node);
    }

    /**
     * 删除权限节点（有子节点或被角色/用户引用时拒绝）。
     *
     * @param id 权限 ID
     */
    @Override
    @Transactional
    public void delete(Long id) {
        Permission node = getPermissionOrThrow(id);
        boolean hasChild = permissionRepository.existsByParentId(id);
        boolean referenced = rolePermissionRepository.countByPermissionId(id) > 0
                || userPermissionRepository.countByPermissionId(id) > 0;
        if (hasChild || referenced) {
            throw new BusinessException(ErrorCode.CONFLICT_STATUS,
                    "该权限仍有子节点或被角色、用户引用，请先解除");
        }
        permissionRepository.delete(node);
    }

    /**
     * 按 ID 取权限，不存在则 404。
     *
     * @param id 权限 ID
     * @return 权限实体
     */
    @Override
    @Transactional(readOnly = true)
    public Permission getPermissionOrThrow(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权限节点不存在或已被删除");
        }
        return permissionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权限节点不存在或已被删除"));
    }

    /**
     * 取父节点：{@code 0} 表示根（返回 null），其余必须存在否则 400。
     *
     * @param parentId 父节点 ID
     * @return 父节点（根为 null）
     */
    private Permission requireParent(Long parentId) {
        if (parentId == 0L) {
            return null;
        }
        return permissionRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "父节点不存在，请重新选择"));
    }

    /**
     * 计算子节点的 {@code ancestors}：根为 "0"，否则父路径 + 父 ID。
     *
     * @param parent 父节点（null = 根）
     * @return 祖级路径
     */
    private String childAncestors(Permission parent) {
        return parent == null ? "0" : parent.getAncestors() + "," + parent.getId();
    }

    /**
     * 层级校验：DIR 只能在根下，MENU 必须在 DIR 下，BUTTON 必须在 MENU 下。
     *
     * @param type   目标类型
     * @param parent 父节点（null = 根）
     */
    private void assertHierarchy(PermType type, Permission parent) {
        boolean ok = switch (type) {
            case DIR -> parent == null;
            case MENU -> parent != null && parent.getType() == PermType.DIR;
            case BUTTON -> parent != null && parent.getType() == PermType.MENU;
        };
        if (!ok) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, HIERARCHY_MESSAGE);
        }
    }

    /**
     * 目录与菜单必须填前端路由。
     *
     * @param type 权限类型
     * @param path 路由
     */
    private void assertPath(PermType type, String path) {
        if (type != PermType.BUTTON && !StringUtils.hasText(path)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目录与菜单必须填写前端路由");
        }
    }

    /**
     * 环检测：目标父节点是否为自身或自身子孙。
     *
     * @param parent  目标父节点
     * @param oldPath 当前节点的完整路径（= 自身 ancestors + "," + 自身 id）
     * @return true = 会形成环
     */
    private boolean isSelfOrDescendant(Permission parent, String oldPath) {
        String parentPath = parent.getAncestors() + "," + parent.getId();
        return oldPath.equals(parentPath) || parentPath.startsWith(oldPath + ",");
    }
}
