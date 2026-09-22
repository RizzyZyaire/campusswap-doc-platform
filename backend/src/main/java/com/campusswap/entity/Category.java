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
 * 文档分类（doc_category）：最多 3 层，树形靠 {@code parent_id + ancestors} 落库，不做自关联对象映射。
 *
 * <p>查「某分类及其全部子孙下的文档」用 {@code ancestors LIKE '0,1%'}，无递归 SQL。</p>
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
@Table(name = "doc_category")
@SQLDelete(sql = "UPDATE doc_category SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
public class Category extends BaseEntity {

    /** 分类名称。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 父分类 ID（0 = 顶级分类）。 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    /** 祖级路径（逗号分隔，如 0,1）。 */
    @Column(name = "ancestors", length = 500, nullable = false)
    private String ancestors;

    /** 同级展示排序号（升序）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
