package com.mewchat.common.constant;

/**
 * 对话与 Agent 相关常量。
 *
 * <p>SSE 事件名、角色名这类值会被前端硬编码引用，集中在此便于前后端对齐。
 *
 * @author MewChat
 */
public final class ChatConstants {

    /**
     * 工具类，禁止实例化。
     */
    private ChatConstants() {
    }

    /* ==================== 角色 ==================== */

    /** 用户角色 */
    public static final String ROLE_USER = "user";

    /** 助手角色 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 系统角色 */
    public static final String ROLE_SYSTEM = "system";

    /* ==================== SSE 事件名 ==================== */

    /** 常规消息分片，前端收到后追加到当前气泡 */
    public static final String SSE_EVENT_MESSAGE = "message";

    /** 流结束标记，前端收到后关闭连接 */
    public static final String SSE_EVENT_DONE = "done";

    /** 错误事件，前端收到后提示用户 */
    public static final String SSE_EVENT_ERROR = "error";

    /** 会话 ID 事件，流开始时下发，供前端后续请求携带 */
    public static final String SSE_EVENT_SESSION = "session";

    /**
     * 流程状态事件。
     *
     * <p>每次流程进入一个状态就推送一次，data 为该状态的中文名。
     * 用途是让前端显示"正在查询订单…"这类进度提示：检索与工具调用都有真实耗时，
     * 期间一个字都没有的话用户会以为卡住了。
     */
    public static final String SSE_EVENT_STATE = "state";

    /* ==================== 会话 ==================== */

    /** 单条用户消息长度上限，防止超长输入打爆 token 预算 */
    public static final int MAX_MESSAGE_LENGTH = 2000;
}
