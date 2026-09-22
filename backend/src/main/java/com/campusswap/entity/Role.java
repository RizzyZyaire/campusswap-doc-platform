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
 * 角色（sys_role）：{@code code} 全局唯一且内置角色不可改；删除前需校验是否被引用。
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
@Table(name = "sys_role")
// 软删除 + 唯一键共存：code 上有唯一索引 uk_sys_role_code，只置 deleted=1 会让该编码被"占位"，
// 之后再建同名编码会撞唯一索引（500）。因此删除时把 code 改写成 code#del#id，释放唯一键。
@SQLDelete(sql = "UPDATE sys_role SET deleted = 1, code = CONCAT(LEFT(code, 30), '#del#', id) WHERE id = ?")
@SQLRestriction("deleted = 0")
public class Role extends BaseEntity {

    /** 角色名称。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 角色编码（STAFF / DOC_ADMIN / SYS_ADMIN 或自定义）。 */
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    /** 角色职责描述。 */
    @Column(name = "description", length = 255)
    private String description;

    /** 是否内置角色：1 = 内置（禁止删除、禁止改 code）。 */
    @Column(name = "is_builtin", columnDefinition = "TINYINT", nullable = false)
    private Integer isBuiltin;

    /** 展示排序号。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
