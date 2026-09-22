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
 * 用户直授权限（sys_user_permission）：个别补权，与角色权限合并取并集。
 *
 * <p>M3 只读不写（无补权接口），因此本实体只被 {@code UserPermissionRepository} 查询使用。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = {"userId", "permissionId"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(UserPermissionId.class)
@Table(name = "sys_user_permission")
public class UserPermission {

    /** 用户 ID（关联 sys_user.id）。 */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 权限点 ID（关联 sys_permission.id）。 */
    @Id
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    /** 直授时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 业务构造器：只给两个主键字段。
     *
     * @param userId       用户 ID
     * @param permissionId 权限点 ID
     */
    public UserPermission(Long userId, Long permissionId) {
        this.userId = userId;
        this.permissionId = permissionId;
    }
}
