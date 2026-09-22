package com.campusswap.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link UserRole} 的复合主键 {@code (user_id, role_id)}。
 *
 * @author Zyaire
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID。 */
    private Long userId;

    /** 角色 ID。 */
    private Long roleId;
}
