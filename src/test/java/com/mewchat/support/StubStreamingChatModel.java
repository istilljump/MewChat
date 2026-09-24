package com.mewchat.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性流式对话模型替身（测试专用）。
 *
 * <p>测试要验证的是"异步回调如何被桥接回同步流程""片段与完整响应不一致时采信谁"
 * "失败发生在推送中途会怎样"这类分支，这些都需要精确控制回调的时机与顺序，
 * 真实模型给不了。<b>不消耗 token，也不会因为网络抖动而变成偶发失败。</b>
 *
 * <p>与 {@link StubChatModel} 同样的注意点：{@code StreamingChatModel} 的方法都是
 * default 方法，最终汇聚到 {@code chat(ChatRequest, handler)}。覆写这一个入口，
 * 业务代码无论调用哪个重载都会被拦截 —— 若覆写错误的重载，
 * 会出现"回调从未触发"的假象，而那不是被测代码的问题。
 *
 * @author MewChat
 */
public class StubStreamingChatModel implements StreamingChatModel {

    /** 默认的片段切分，拼接后即为完整回复 */
    public static final List<String> DEFAULT_FRAGMENTS =
            List.of("根据平台规则，", "签收后 7 天内", "可申请无理由退货。");

    /** 固定的 token 用量，用于验证用量是否被回填 */
    private static final TokenUsage TOKEN_USAGE = new TokenUsage(50, 20, 70);

    private List<String> fragments = DEFAULT_FRAGMENTS;

    private Throwable error;

    private boolean errorBeforeFragments;

    private boolean neverInvokeCallback;

    private String completeTextOverride;

    private final AtomicInteger invocations = new AtomicInteger();

    /**
     * 默认片段拼接后的完整文本。
     *
     * @return 完整回复文本
     */
    public static String defaultText() {
        return String.join("", DEFAULT_FRAGMENTS);
    }

    /**
     * 重置为默认行为。
     */
    public void reset() {
        fragments = DEFAULT_FRAGMENTS;
        error = null;
        errorBeforeFragments = false;
        neverInvokeCallback = false;
        completeTextOverride = null;
        invocations.set(0);
    }

    /**
     * 设置推送的片段。
     *
     * @param fragments 片段列表
     */
    public void setFragments(List<String> fragments) {
        this.fragments = fragments;
    }

    /**
     * 设置完整响应里的文本。
     *
     * <p>用于构造"片段与完整响应不一致"这种兼容端点差异。
     *
     * @param text 完整响应文本
     */
    public void setCompleteTextOverride(String text) {
        this.completeTextOverride = text;
    }

    /**
     * 推送完全部片段后再失败。
     *
     * @param error 错误
     */
    public void failAfterFragments(Throwable error) {
        this.error = error;
    }

    /**
     * 一个片段都不推就失败。
     *
     * @param error 错误
     */
    public void failBeforeFragments(Throwable error) {
        this.errorBeforeFragments = true;
        this.error = error;
    }

    /**
     * 设置回调是否完全不触发，用于模拟客户端缺陷导致的"永远等不到结果"。
     *
     * @param neverInvoke true 表示不触发任何回调
     */
    public void setNeverInvokeCallback(boolean neverInvoke) {
        this.neverInvokeCallback = neverInvoke;
    }

    /**
     * 已调用次数，用于验证"非流式路径不会走流式模型"。
     *
     * @return 调用次数
     */
    public int invocationCount() {
        return invocations.get();
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        invocations.incrementAndGet();

        if (neverInvokeCallback) {
            // 什么都不做：调用方必须靠自己的等待上界脱身，否则永远挂着
            return;
        }
        if (errorBeforeFragments) {
            handler.onError(error);
            return;
        }

        for (String fragment : fragments) {
            handler.onPartialResponse(fragment);
        }

        if (error != null) {
            handler.onError(error);
            return;
        }

        String text = completeTextOverride != null
                ? completeTextOverride
                : String.join("", fragments);
        handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(TOKEN_USAGE)
                .build());
    }
}
