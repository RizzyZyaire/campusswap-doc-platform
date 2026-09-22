package com.campusswap.entity.enums;

/**
 * 权限节点类型（对应 sys_permission.type，VARCHAR(32)）。
 *
 * <p>三层树：目录 → 菜单 → 按钮。表结构用 parent_id + ancestors 表达层级，
 * 前端按 type 决定渲染成一级菜单、二级菜单还是按钮。</p>
 *
 * @author Zyaire
 */
public enum PermType {

    /** 目录层（一级导航）。 */
    DIR("目录"),

    /** 菜单层（二级页面/路由）。 */
    MENU("菜单"),

    /** 按钮层（具体操作点，接口鉴权用）。 */
    BUTTON("按钮");

    private final String label;

    PermType(String label) {
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
