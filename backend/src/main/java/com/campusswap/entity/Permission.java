package com.campusswap.entity;

import com.campusswap.entity.enums.PermType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 功能权限（sys_permission）：三层树 DIR / MENU / BUTTON，树形靠 {@code parent_id + ancestors} 落库，不做自关联对象映射。
 *
 * <p>查「某节点下全部权限」用 {@code ancestors LIKE '0,1%'}，一次查询取全树后在内存里组装（课件 3.1 红线二）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sys_permission")
// 软删除 + 唯一键共存：code 上有唯一索引 uk_sys_permission_code，删除时改写成 code#del#id 释放唯一键，
// 否则同一编码再也无法新建（唯一索引被已删除行占位）。
@SQLDelete(sql = "UPDATE sys_permission SET deleted = 1, code = CONCAT(LEFT(code, 30), '#del#', id)"
        + " WHERE id = ?")
@SQLRestriction("deleted = 0")
public class Permission extends BaseEntity {

    /** 权限名称（如：新建文档）。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 权限编码（全局唯一，形如 doc:create）。 */
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    /** 权限类型：DIR / MENU / BUTTON（字符串落库）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 32, nullable = false)
    private PermType type;

    /** 父节点 ID（0 = 根节点）。 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    /** 祖级路径（逗号分隔，如 0,1,10）。 */
    @Column(name = "ancestors", length = 500, nullable = false)
    private String ancestors;

    /** 前端路由（目录/菜单层使用，按钮层为空）。 */
    @Column(name = "path", length = 255)
    private String path;

    /** 前端图标名（可空）。 */
    @Column(name = "icon", length = 64)
    private String icon;

    /** 同级展示排序号（升序）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
