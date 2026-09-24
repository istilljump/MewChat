package com.mewchat.common.result;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 全局统一响应包装类。
 *
 * <p>所有对外接口（含 SSE 流式接口的每一帧 data）都必须返回该结构，
 * 保证前端可以用同一套逻辑解析。
 *
 * <p>约定：
 * <ul>
 *     <li>{@code code == 0} 表示成功，此时 {@code data} 为业务数据</li>
 *     <li>{@code code != 0} 表示失败，此时 {@code data} 为 null，错误原因看 {@code message}</li>
 * </ul>
 *
 * <p>序列化时 {@code null} 字段被忽略，避免前端收到一堆无意义的空字段。
 *
 * @param <T> 业务数据类型
 * @author MewChat
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应码，0 表示成功 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    /**
     * 服务器响应时间，便于前端排查时序问题。
     *
     * <p>必须用 {@code @JsonFormat} 显式指定格式：yml 中的
     * {@code spring.jackson.date-format} 只作用于 {@code java.util.Date}，
     * 对 {@code LocalDateTime} 不生效，不指定会输出带纳秒的 ISO-8601 串。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime timestamp;

    /**
     * 无参构造，保留给 Jackson 反序列化使用。
     */
    public Result() {
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 全参构造。
     *
     * @param code    响应码
     * @param message 提示信息
     * @param data    业务数据
     */
    public Result(int code, String message, T data) {
        this();
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /* ==================== 成功 ==================== */

    /**
     * 构造无数据的成功响应，用于新增/删除等不关心返回值的场景。
     *
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 构造带数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 构造带数据与自定义提示的成功响应。
     *
     * @param data    业务数据
     * @param message 自定义提示信息
     * @param <T>     业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data, String message) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /* ==================== 失败 ==================== */

    /**
     * 使用默认错误码与提示构造失败响应。
     *
     * @param <T> 业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error() {
        return error(ResultCode.FAILED);
    }

    /**
     * 按枚举错误码构造失败响应（提示信息取枚举默认值）。
     *
     * @param resultCode 错误码枚举
     * @param <T>        业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 按枚举错误码构造失败响应，并覆盖提示信息。
     * 适用于需要把具体原因（如"订单号 XXX 不存在"）透给前端的场景。
     *
     * @param resultCode 错误码枚举
     * @param message    自定义提示信息
     * @param <T>        业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /**
     * 按自定义错误码与提示构造失败响应。
     *
     * @param code    错误码
     * @param message 提示信息
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 判断本次响应是否成功。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    /* ==================== Getter / Setter ==================== */

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
