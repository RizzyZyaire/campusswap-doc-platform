package com.campusswap.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link UserPermission} 的复合主键 {@code (user_id, permission_id)}。
 *
 * @author Zyaire
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class UserPermissionId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID。 */
    private Long userId;

    /** 权限点 ID。 */
    private Long permissionId;
}
