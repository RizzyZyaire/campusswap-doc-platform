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
 * 角色-权限关联（sys_role_permission）：授权/回收一律「先按 roleId 清空再批量插入」，避免逐条 diff。
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = {"roleId", "permissionId"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(RolePermissionId.class)
@Table(name = "sys_role_permission")
public class RolePermission {

    /** 角色 ID（关联 sys_role.id）。 */
    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    /** 权限点 ID（关联 sys_permission.id）。 */
    @Id
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    /** 配置时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 业务构造器：只给两个主键字段（{@code created_at} 由 {@code @CreationTimestamp} 填充）。
     *
     * @param roleId       角色 ID
     * @param permissionId 权限点 ID
     */
    public RolePermission(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }
}
