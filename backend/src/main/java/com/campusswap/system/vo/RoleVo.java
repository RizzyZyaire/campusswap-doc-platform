package com.campusswap.system.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.Role;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色出参（API_SPECIFICATION §2.7.2，GLOSSARY §3.7 的 {@code RoleVo}）。
 *
 * <p>角色权限规模<b>不</b>在本 VO 中返回（避免为一个数字多查一次关联表），
 * 需要时调 {@code GET /api/roles/{id}/permissions}。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class RoleVo extends AuditVo {

    /** 角色 ID（字符串）。 */
    private String id;

    /** 角色名称。 */
    private String name;

    /** 角色编码（创建后不可修改）。 */
    private String code;

    /** 角色描述（可空）。 */
    private String description;

    /** 是否内置角色：1 = 内置（不可删除）。 */
    private Integer isBuiltin;

    /** 排序号（升序）。 */
    private Integer sortOrder;

    /**
     * 由实体组装。
     *
     * @param role 角色实体
     * @return 角色出参
     */
    public static RoleVo of(Role role) {
        RoleVo vo = new RoleVo();
        vo.fillAudit(role);
        vo.id = IdUtil.toStr(role.getId());
        vo.name = role.getName();
        vo.code = role.getCode();
        vo.description = role.getDescription();
        vo.isBuiltin = role.getIsBuiltin();
        vo.sortOrder = role.getSortOrder();
        return vo;
    }
}
