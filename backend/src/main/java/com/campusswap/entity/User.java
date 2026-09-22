package com.campusswap.entity;

import com.campusswap.entity.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 用户（sys_user）：一人一部门；角色关系一律走 sys_user_role 中间表，本实体不持有角色列。
 *
 * <p>密码只存 BCrypt 哈希（{@code password_hash}），任何 VO 都不得带出该字段（老师红线 R5）。</p>
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
@Table(name = "sys_user")
@SQLDelete(sql = "UPDATE sys_user SET deleted = 1 WHERE id = ?")
@SQLRestriction("deleted = 0")
public class User extends BaseEntity {

    /** 登录账号（工号），全局唯一。 */
    @Column(name = "username", length = 64, nullable = false)
    private String username;

    /** BCrypt 密码哈希。 */
    @Column(name = "password_hash", length = 128, nullable = false)
    private String passwordHash;

    /** 真实姓名。 */
    @Column(name = "real_name", length = 64, nullable = false)
    private String realName;

    /** 归属部门 ID。 */
    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    /** 工作邮箱（可空）。 */
    @Column(name = "email", length = 128)
    private String email;

    /** 手机号（可空）。 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 头像相对 URL（可空）。 */
    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    /** 账号状态：ACTIVE / LOCKED / DISABLED（字符串落库，禁 ORDINAL）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private UserStatus status;

    /** 最后一次登录成功时间（可空）。 */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
