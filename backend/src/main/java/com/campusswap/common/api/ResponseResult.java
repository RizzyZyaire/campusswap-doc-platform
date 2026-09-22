package com.campusswap.common.api;

/**
 * 全平台统一响应体。
 *
 * <p>前端只需一套拦截逻辑：{@code code !== 200} 时提示 {@code message}。
 * 与 docs/02-design/API_SPECIFICATION.md §2.1 一致。</p>
 *
 * @param <T> 业务数据类型
 * @author Zyaire
 */
public record ResponseResult<T>(int code, String message, T data) {

    /**
     * 成功响应（带数据）。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 统一响应体
     */
    public static <T> ResponseResult<T> ok(T data) {
        return new ResponseResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功响应（无数据，用于删除、发布等动作类接口）。
     *
     * @param <T> 数据类型
     * @return 统一响应体
     */
    public static <T> ResponseResult<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应（使用错误码默认中文提示）。
     *
     * @param errorCode 错误码
     * @param <T>       数据类型
     * @return 统一响应体
     */
    public static <T> ResponseResult<T> fail(ErrorCode errorCode) {
        return new ResponseResult<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败响应（自定义中文提示，用于校验失败与业务细化提示）。
     *
     * @param errorCode 错误码
     * @param message   自定义中文提示
     * @param <T>       数据类型
     * @return 统一响应体
     */
    public static <T> ResponseResult<T> fail(ErrorCode errorCode, String message) {
        return new ResponseResult<>(errorCode.getCode(), message, null);
    }
}
