package com.mewchat.agent;

import com.mewchat.rag.retrieval.RetrievedChunk;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次对话处理的结果。
 *
 * <p>{@code ChatSupervisor.process()} 的返回值，是编排层对外的唯一契约。
 * 除了给用户看的 {@code content}，还带上意图、置信度、引用来源、状态轨迹等
 * 便于接口层与前端做展示、埋点、排查的信息。
 *
 * @author MewChat
 */
@Getter
@ToString
@Builder
public class ChatReply {

    /** 会话业务ID */
    private final String sessionId;

    /**
     * 本轮助手回复落库后的消息ID，供前端对这条回答提交反馈。
     *
     * <p>落库失败时为 null —— 那种情况下前端会隐藏反馈按钮，
     * 而不是点了之后收到"消息不存在"。
     */
    private final Long messageId;

    /** 回复文本，直接展示给用户 */
    private final String content;

    /** 本轮识别出的意图 */
    private final IntentType intent;

    /** 本轮有效置信度 */
    private final BigDecimal confidence;

    /** 引用的知识切片，供前端展示"参考来源" */
    private final List<RetrievedChunk> citations;

    /** 是否建议转人工 */
    private final boolean handoffRequired;

    /** 流程终止时所处的状态（REPLY / CLARIFY / FALLBACK） */
    private final ChatState finalState;

    /** 状态轨迹，便于排查"这轮为什么这么答" */
    private final List<ChatState> visitedStates;

    /** 本轮耗时（毫秒） */
    private final Long costMs;

    /** 输入 token 数 */
    private final Integer promptTokens;

    /** 输出 token 数 */
    private final Integer completionTokens;

    /** 总 token 数 */
    private final Integer totalTokens;

    /**
     * 是否走到了兜底分支。
     *
     * @return true 表示本轮未能给出可靠答案
     */
    public boolean isFallback() {
        return finalState == ChatState.FALLBACK;
    }

    /**
     * 是否在向用户追问。
     *
     * @return true 表示本轮是反问、等待用户补充信息
     */
    public boolean isClarifying() {
        return finalState == ChatState.CLARIFY;
    }
}
