package com.campusswap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 部门（sys_dept）：组织层级树，用 parent_id + ancestors 表达，禁止自关联对象。
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
@Table(name = "sys_dept")
@SQLDelete(sql = "UPDATE sys_dept SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
public class Dept extends BaseEntity {

    /** 部门名称。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 上级部门 ID（0 = 顶级）。 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    /** 祖级路径，如 {@code 0,1}。 */
    @Column(name = "ancestors", length = 500, nullable = false)
    private String ancestors;

    /** 同级排序号。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
