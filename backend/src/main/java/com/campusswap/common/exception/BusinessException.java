package com.campusswap.common.exception;

import com.campusswap.common.api.ErrorCode;

/**
 * 业务异常：Service 层所有可预期的失败都用它抛出，由 {@link GlobalExceptionHandler} 统一翻译成响应体。
 *
 * <p>用法：{@code throw new BusinessException(ErrorCode.CONFLICT_STATUS, "回收站文档需先恢复为草稿");}</p>
 *
 * @author Zyaire
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码（与 HTTP 状态码一致）。 */
    private final ErrorCode errorCode;

    /**
     * 用错误码的默认中文提示构造异常。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 用自定义中文提示构造异常（提示会直接回传给前端）。
     *
     * @param errorCode 错误码
     * @param message   自定义中文提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
