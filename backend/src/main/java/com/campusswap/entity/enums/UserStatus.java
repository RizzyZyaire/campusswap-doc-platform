package com.campusswap.entity.enums;

/**
 * 用户账号状态（对应 sys_user.status，VARCHAR(32)）。
 *
 * <p>只有 {@link #ACTIVE} 允许登录；其余状态登录返回 403 USER_DISABLED（US-01 AC-01.3）。</p>
 *
 * @author Zyaire
 */
public enum UserStatus {

    /** 正常：可登录。 */
    ACTIVE("正常"),

    /** 冻结：禁止登录（保留数据，等待解冻）。 */
    LOCKED("已冻结"),

    /** 停用：禁止登录，且登录中的会话会被强制下线。 */
    DISABLED("已停用");

    private final String label;

    UserStatus(String label) {
        this.label = label;
    }

    /**
     * 获取中文名称。
     *
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否为可登录状态。
     *
     * @return true = 允许登录
     */
    public boolean canLogin() {
        return this == ACTIVE;
    }
}
