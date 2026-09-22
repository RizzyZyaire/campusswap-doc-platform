package com.campusswap.system.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.Permission;
import com.campusswap.entity.enums.PermType;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 权限节点出参（API_SPECIFICATION §4.4.1，GLOSSARY §3.7 的 {@code PermissionVo}）。
 *
 * <p>三层树（DIR → MENU → BUTTON）由 Service 一次查全后在内存组装：先建 Map，再按 {@code parentId} 挂
 * {@code children}，<b>禁止</b>递归查库（ARCHITECTURE §10.5 铁律二）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class PermissionVo extends AuditVo {

    /** 权限 ID（字符串）。 */
    private String id;

    /** 权限名称。 */
    private String name;

    /** 权限编码，如 {@code doc:publish}。 */
    private String code;

    /** 权限类型：DIR / MENU / BUTTON。 */
    private PermType type;

    /** 父节点 ID（根为 "0"）。 */
    private String parentId;

    /** 祖级路径，如 "0,1,10"。 */
    private String ancestors;

    /** 前端路由（目录/菜单层用，可空）。 */
    private String path;

    /** 前端图标名（可空）。 */
    private String icon;

    /** 同级排序号。 */
    private Integer sortOrder;

    /** 子节点（叶子为空数组）。 */
    private List<PermissionVo> children = new ArrayList<>();

    /**
     * 由实体组装（不含 {@code children}，由 Service 组树时填）。
     *
     * @param permission 权限实体
     * @return 权限节点出参
     */
    public static PermissionVo of(Permission permission) {
        PermissionVo vo = new PermissionVo();
        vo.fillAudit(permission);
        vo.id = IdUtil.toStr(permission.getId());
        vo.name = permission.getName();
        vo.code = permission.getCode();
        vo.type = permission.getType();
        vo.parentId = IdUtil.toStr(permission.getParentId());
        vo.ancestors = permission.getAncestors();
        vo.path = permission.getPath();
        vo.icon = permission.getIcon();
        vo.sortOrder = permission.getSortOrder();
        return vo;
    }
}
