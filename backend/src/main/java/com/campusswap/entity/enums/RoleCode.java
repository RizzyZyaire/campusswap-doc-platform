package com.campusswap.entity.enums;

/**
 * 内置角色编码（对应 sys_role.code，VARCHAR(64)）。
 *
 * <p>三个内置角色的默认授权见 docs/01-requirements/PRD.md §3.3；
 * 自定义角色也会写进同一列，因此业务代码判断“是不是管理员”时用 {@link #SYS_ADMIN} 等枚举值比较，
 * 而不是硬编码字符串。</p>
 *
 * @author Zyaire
 */
public enum RoleCode {

    /** 普通员工。 */
    STAFF("普通员工"),

    /** 文档管理员。 */
    DOC_ADMIN("文档管理员"),

    /** 系统管理员。 */
    SYS_ADMIN("系统管理员");

    private final String label;

    RoleCode(String label) {
        this.label = label;
    }

    /**
     * 获取中文名称。
     *
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }
}
