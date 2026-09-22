package com.campusswap.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@link DeptRole} 的复合主键 {@code (dept_id, role_id)}。
 *
 * @author Zyaire
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class DeptRoleId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 部门 ID。 */
    private Long deptId;

    /** 角色 ID。 */
    private Long roleId;
}
