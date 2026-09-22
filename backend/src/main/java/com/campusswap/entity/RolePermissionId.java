package com.campusswap.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link RolePermission} 的复合主键 {@code (role_id, permission_id)}。
 *
 * @author Zyaire
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色 ID。 */
    private Long roleId;

    /** 权限点 ID。 */
    private Long permissionId;
}
