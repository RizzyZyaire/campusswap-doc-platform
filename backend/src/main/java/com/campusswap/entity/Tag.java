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
 * 文档标签（doc_tag）：扁平字典、名称全局唯一，{@code use_count} 为冗余计数（打/取消标签时原子增减）。
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
@Table(name = "doc_tag")
// 软删除 + 唯一键共存：name 上有唯一索引 uk_doc_tag_name，删除时改写成 name#del#id 释放唯一键，
// 否则同名标签再也建不出来（唯一索引被已删除行占位），见 ARCHITECTURE §10 规约 18。
@SQLDelete(sql = "UPDATE doc_tag SET deleted = 1, name = CONCAT(LEFT(name, 30), '#del#', id) WHERE id = ?")
@SQLRestriction("deleted = 0")
public class Tag extends BaseEntity {

    /** 标签名称（全局唯一）。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 被引用次数（冗余计数）。 */
    @Column(name = "use_count", nullable = false)
    private Integer useCount;
}
