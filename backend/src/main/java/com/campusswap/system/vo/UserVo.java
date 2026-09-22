package com.campusswap.system.vo;

import com.campusswap.common.api.AuditVo;
import com.campusswap.entity.User;
import com.campusswap.entity.enums.UserStatus;
import com.campusswap.common.util.IdUtil;
import com.campusswap.common.util.TimeUtil;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户列表/详情出参（API_SPECIFICATION §2.7.2，GLOSSARY §3.7 的 {@code UserVo}）。
 *
 * <p>{@code deptName} 与 {@code roles} 由 Service 批量补齐（{@code IN} + Map），
 * 本 VO 不含 {@code passwordHash}（NFR-S1），也不含 {@code deleted}。</p>
 *
 * @author Zyaire
 */
@Getter
@Setter
public class UserVo extends AuditVo {

    /** 用户 ID（字符串）。 */
    private String id;

    /** 登录名（工号）。 */
    private String username;

    /** 真实姓名。 */
    private String realName;

    /** 部门 ID（字符串）。 */
    private String deptId;

    /** 部门名称（VO 独有字段，不落库）。 */
    private String deptName;

    /** 邮箱（可空）。 */
    private String email;

    /** 手机号（可空）。 */
    private String phone;

    /** 头像相对 URL（可空）。 */
    private String avatarUrl;

    /** 账号状态：ACTIVE / LOCKED / DISABLED。 */
    private UserStatus status;

    /** 角色编码集合（如 {@code ["STAFF"]}）。 */
    private List<String> roles;

    /** 最后登录时间，{@code yyyy-MM-dd HH:mm:ss}（可空）。 */
    private String lastLoginAt;

    /**
     * 由实体组装（{@code deptName} / {@code roles} 由调用方批量补齐后传入）。
     *
     * @param user     用户实体
     * @param deptName 部门名称（可为 null，表示未匹配到部门）
     * @param roles    角色编码集合
     * @return 用户出参
     */
    public static UserVo of(User user, String deptName, List<String> roles) {
        UserVo vo = new UserVo();
        vo.fillAudit(user);
        vo.id = IdUtil.toStr(user.getId());
        vo.username = user.getUsername();
        vo.realName = user.getRealName();
        vo.deptId = IdUtil.toStr(user.getDeptId());
        vo.deptName = deptName;
        vo.email = user.getEmail();
        vo.phone = user.getPhone();
        vo.avatarUrl = user.getAvatarUrl();
        vo.status = user.getStatus();
        vo.roles = roles == null ? List.of() : roles;
        vo.lastLoginAt = TimeUtil.format(user.getLastLoginAt());
        return vo;
    }
}
