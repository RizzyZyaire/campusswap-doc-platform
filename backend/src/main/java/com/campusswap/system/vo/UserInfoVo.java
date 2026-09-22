package com.campusswap.system.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.common.util.IdUtil;
import com.campusswap.entity.User;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 当前登录用户信息出参（API_SPECIFICATION §2.7.2，GLOSSARY §3.7 的 {@code UserInfoVo}）。
 *
 * <p>比 {@code UserVo} 少了邮箱/手机/状态/最后登录时间，多了 {@code permissions}（权限码集合）——
 * 它是前端渲染菜单与按钮的唯一依据。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class UserInfoVo extends AuditVo {

    /** 用户 ID（字符串）。 */
    private String id;

    /** 登录名（工号）。 */
    private String username;

    /** 真实姓名。 */
    private String realName;

    /** 部门 ID（字符串）。 */
    private String deptId;

    /** 部门名称。 */
    private String deptName;

    /** 角色编码集合。 */
    private List<String> roles;

    /** 头像相对 URL（可空）。 */
    private String avatarUrl;

    /** 权限码集合（角色权限 ∪ 部门角色权限 ∪ 用户直授权限，去重）。 */
    private List<String> permissions;

    /**
     * 由实体组装。
     *
     * @param user        用户实体
     * @param deptName    部门名称
     * @param roles       角色编码集合
     * @param permissions 权限码集合
     * @return 登录用户信息
     */
    public static UserInfoVo of(User user, String deptName, List<String> roles, List<String> permissions) {
        UserInfoVo vo = new UserInfoVo();
        vo.fillAudit(user);
        vo.id = IdUtil.toStr(user.getId());
        vo.username = user.getUsername();
        vo.realName = user.getRealName();
        vo.deptId = IdUtil.toStr(user.getDeptId());
        vo.deptName = deptName;
        vo.roles = roles == null ? List.of() : roles;
        vo.avatarUrl = user.getAvatarUrl();
        vo.permissions = permissions == null ? List.of() : permissions;
        return vo;
    }
}
