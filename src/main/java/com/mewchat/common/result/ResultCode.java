package com.mewchat.common.result;

import java.io.Serializable;

/**
 * 全局统一响应码。
 *
 * <p>约定：{@code code == 0} 表示成功，非 0 表示失败。
 * 错误码按业务域分段，便于排查时快速定位来源：
 * <ul>
 *     <li>1xxxx —— 通用/参数类错误</li>
 *     <li>2xxxx —— 认证与权限</li>
 *     <li>3xxxx —— Agent 编排</li>
 *     <li>4xxxx —— RAG 检索</li>
 *     <li>5xxxx —— 业务工具（订单/商品/物流）</li>
 * </ul>
 *
 * @author MewChat
 */
public enum ResultCode {

    /* ==================== 通用 ==================== */
    SUCCESS(0, "操作成功"),
    FAILED(10000, "操作失败"),
    PARAM_INVALID(10001, "请求参数不合法"),
    NOT_FOUND(10002, "资源不存在"),
    SYSTEM_ERROR(10003, "系统繁忙，请稍后重试"),

    /* ==================== 认证与权限 ==================== */
    UNAUTHORIZED(20001, "未登录或登录已过期"),
    FORBIDDEN(20002, "无访问权限"),

    /* ==================== Agent 编排 ==================== */
    AGENT_ERROR(30001, "智能客服处理异常"),
    AGENT_TIMEOUT(30002, "智能客服响应超时"),
    AGENT_ROUTE_FAILED(30003, "未能识别用户意图"),

    /* ==================== RAG 检索 ==================== */
    RAG_ERROR(40001, "知识检索异常"),
    RAG_EMPTY_RESULT(40002, "未检索到相关知识"),
    VECTOR_STORE_ERROR(40003, "向量库访问异常"),

    /* ==================== 业务工具 ==================== */
    TOOL_ERROR(50001, "工具调用异常"),
    ORDER_NOT_FOUND(50002, "订单不存在"),
    PRODUCT_NOT_FOUND(50003, "商品不存在"),
    LOGISTICS_NOT_FOUND(50004, "物流信息不存在");

    /** 响应码，0 表示成功 */
    private final int code;

    /** 默认提示信息，面向调用方，不暴露内部实现细节 */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 判断是否为成功码。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
