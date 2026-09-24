package com.mewchat.common.exception;

import com.mewchat.common.result.ResultCode;

/**
 * 业务异常。
 *
 * <p>凡是"可预期的业务失败"（如订单不存在、参数不合法）都应抛出本异常，
 * 由 {@link GlobalExceptionHandler} 统一转成 {@link com.mewchat.common.result.Result} 返回。
 * 这样各层只需关注"失败了"，不必层层传递错误码。
 *
 * <p>不要用它包装系统级异常（如数据库连接失败、NPE），那些应交给兜底处理并记录 error 日志。
 *
 * @author MewChat
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 关联的错误码 */
    private final int code;

    /**
     * 以错误码枚举构造，提示信息取枚举默认值。
     *
     * @param resultCode 错误码枚举
     */
    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 以错误码枚举构造，并覆盖提示信息。
     *
     * @param resultCode 错误码枚举
     * @param message    自定义提示信息
     */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * 以错误码枚举构造，并保留原始异常作为 cause，便于日志中追溯根因。
     *
     * @param resultCode 错误码枚举
     * @param message    自定义提示信息
     * @param cause      原始异常
     */
    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.code = resultCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
