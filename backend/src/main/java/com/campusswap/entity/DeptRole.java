package com.campusswap.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 部门-角色关联（sys_dept_role）：部门绑定角色后，部门成员自动继承该角色权限（权限并集的一部分）。
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = {"deptId", "roleId"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(DeptRoleId.class)
@Table(name = "sys_dept_role")
public class DeptRole {

    /** 部门 ID（关联 sys_dept.id）。 */
    @Id
    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    /** 角色 ID（关联 sys_role.id）。 */
    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    /** 绑定时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 业务构造器：只给两个主键字段（{@code created_at} 由 {@code @CreationTimestamp} 填充）。
     *
     * @param deptId 部门 ID
     * @param roleId 角色 ID
     */
    public DeptRole(Long deptId, Long roleId) {
        this.deptId = deptId;
        this.roleId = roleId;
    }
}
