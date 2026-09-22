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
 * 用户-角色关联（sys_user_role）：复合主键、无 id 列、无软删除列，因此<b>不做软删除</b>（解绑即物理 DELETE）。
 *
 * <p>写入纪律：角色绑定/解绑一律操作本实体（课件 2.1：中间表走显式实体，禁走集合的 add/remove）。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = {"userId", "roleId"})
@NoArgsConstructor
@AllArgsConstructor
@Entity
@IdClass(UserRoleId.class)
@Table(name = "sys_user_role")
public class UserRole {

    /** 用户 ID（关联 sys_user.id）。 */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 角色 ID（关联 sys_role.id）。 */
    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    /** 授权时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 业务构造器：只给两个主键字段（{@code created_at} 由 {@code @CreationTimestamp} 填充）。
     *
     * @param userId 用户 ID
     * @param roleId 角色 ID
     */
    public UserRole(Long userId, Long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }
}
