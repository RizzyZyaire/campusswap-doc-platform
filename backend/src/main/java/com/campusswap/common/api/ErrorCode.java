package com.campusswap.common.api;

/**
 * 全平台统一错误码与中文提示。
 *
 * <p>与 docs/01-requirements/GLOSSARY.md §4.2、PRD.md §6.1 逐条对应；
 * {@code code} 与 HTTP 状态码保持一致，前端只需判断 {@code code !== 200}。</p>
 *
 * @author Zyaire
 */
public enum ErrorCode {

    /** 成功（文案对齐 API_SPECIFICATION §2.1 的示例响应 `"message": "成功"`）。 */
    SUCCESS(200, "成功"),

    /** 参数校验失败（@Valid 未通过、分页越界、状态取值非法等）。 */
    BAD_REQUEST(400, "参数校验失败"),

    /** 未登录或 token 失效。 */
    UNAUTHORIZED(401, "登录状态已失效，请重新登录"),

    /** 已登录但缺少权限点（或非属主）。 */
    NO_PERMISSION(403, "无权限执行该操作"),

    /** 账号非 ACTIVE 状态，拒绝登录。 */
    USER_DISABLED(403, "账号已停用，请联系管理员"),

    /** 资源不存在。 */
    NOT_FOUND(404, "请求的资源不存在"),

    /** 当前状态不允许该操作（状态机冲突、唯一约束冲突）。 */
    CONFLICT_STATUS(409, "当前状态不允许该操作"),

    /** 服务器内部错误（对外不暴露堆栈）。 */
    SERVER_ERROR(500, "服务器开小差了，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取错误码。
     *
     * @return 与 HTTP 状态码一致的业务码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取默认中文提示。
     *
     * @return 中文提示文案
     */
    public String getMessage() {
        return message;
    }
}
