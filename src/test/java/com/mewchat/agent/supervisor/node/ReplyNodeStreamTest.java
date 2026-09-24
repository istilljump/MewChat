package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.config.LlmProperties;
import com.mewchat.config.RagProperties;
import com.mewchat.support.StubChatModel;
import com.mewchat.support.StubStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回复节点的流式生成测试（纯单元测试，不依赖 Spring 与数据库）。
 *
 * <p>这里验证的是本阶段最容易出错的那部分：<b>把异步回调桥回同步流程</b>。
 * 它一旦写错，表现都很隐蔽 —— 回调没接住会静默走兜底（用户看到"系统繁忙"，
 * 而日志里没有任何异常）；等待没有上界会永久占住线程；
 * 片段与完整响应不一致时选错一侧，会让"用户看到的"和"落库的"不是同一段话。
 * 这些都不可能在手工点一下页面时被发现。
 *
 * @author MewChat
 */
class ReplyNodeStreamTest {

    private final StubChatModel syncModel = new StubChatModel();

    private final StubStreamingChatModel streamingModel = new StubStreamingChatModel();

    private final LlmProperties llmProperties = new LlmProperties();

    private final RagProperties ragProperties = new RagProperties();

    private RecordingListener listener;

    private ReplyNode replyNode;

    @BeforeEach
    void setUp() {
        syncModel.reset();
        streamingModel.reset();
        listener = new RecordingListener();
        replyNode = new ReplyNode(syncModel, streamingModel, llmProperties, ragProperties);
    }

    /* ==================== 正常路径 ==================== */

    /**
     * 片段应按顺序逐个推给回调，最终回复文本是片段的拼接。
     */
    @Test
    void streamingShouldDeliverFragmentsInOrder() {
        ChatContext context = streamingContext();

        ChatState next = replyNode.execute(context);

        assertThat(next).isEqualTo(ChatState.END);
        assertThat(listener.fragments)
                .containsExactlyElementsOf(StubStreamingChatModel.DEFAULT_FRAGMENTS);
        assertThat(context.getReplyText()).isEqualTo(StubStreamingChatModel.defaultText());
        assertThat(context.getTotalTokens()).as("流式返回的 token 用量也要回填").isEqualTo(70);
        assertThat(streamingModel.invocationCount()).isEqualTo(1);
        assertThat(listener.states)
                .as("状态广播是调度器的职责，节点不该越权推送，否则会与调度器重复")
                .isEmpty();
    }

    /**
     * 没有流式回调时必须走同步模型，且完全不碰流式模型。
     */
    @Test
    void nonStreamingCallShouldNotTouchStreamingModel() {
        ChatContext context = ChatContext.builder()
                .sessionId("s-plain")
                .userMessage("七天无理由退货怎么操作")
                .build();

        ChatState next = replyNode.execute(context);

        assertThat(next).isEqualTo(ChatState.END);
        assertThat(context.getReplyText()).isEqualTo(StubChatModel.REPLY_TEXT);
        assertThat(streamingModel.invocationCount())
                .as("非流式路径不应调用流式模型")
                .isZero();
        assertThat(listener.fragments).isEmpty();
    }

    /**
     * 片段与完整响应不一致时，采信<b>已经推送给用户的片段</b>。
     *
     * <p>否则落库的回复会与用户屏幕上的内容不同，刷新页面后"回答变了"。
     */
    @Test
    void deliveredFragmentsShouldWinOverCompleteResponse() {
        streamingModel.setCompleteTextOverride("这是一段完全不同的文本");

        ChatContext context = streamingContext();
        replyNode.execute(context);

        assertThat(context.getReplyText())
                .as("应以已推送给用户的片段为准")
                .isEqualTo(StubStreamingChatModel.defaultText());
    }

    /* ==================== 失败路径 ==================== */

    /**
     * 一个片段都没推就失败 → 降级为兜底。
     */
    @Test
    void failureBeforeFragmentsShouldFallback() {
        streamingModel.failBeforeFragments(new IllegalStateException("模型限流"));

        ChatContext context = streamingContext();
        ChatState next = replyNode.execute(context);

        assertThat(next).isEqualTo(ChatState.FALLBACK);
        assertThat(context.getErrorMessage()).contains("模型限流");
        assertThat(listener.fragments).isEmpty();
    }

    /**
     * 推了一半再失败：<b>仍然降级为兜底，但已经推出去的片段收不回来</b>。
     *
     * <p>这条断言把一个无法回避的事实固定下来：客户端可能已经显示半句话。
     * 因此约定前端必须以 {@code done} 事件里的最终内容为准（替换而非追加），
     * 而不是把"半句话 + 兜底话术"拼在一起显示。
     */
    @Test
    void failureAfterFragmentsShouldFallbackAndLeaveDeliveredText() {
        streamingModel.failAfterFragments(new IllegalStateException("连接中断"));

        ChatContext context = streamingContext();
        ChatState next = replyNode.execute(context);

        assertThat(next).isEqualTo(ChatState.FALLBACK);
        assertThat(context.getErrorMessage()).contains("连接中断");
        assertThat(listener.fragments)
                .as("片段已到达客户端，无法撤回 —— 这正是 done 必须作为权威结果的原因")
                .isNotEmpty();
    }

    /**
     * 回复为空（片段与完整响应都是空白）→ 按生成失败处理。
     */
    @Test
    void blankStreamShouldFallback() {
        streamingModel.setFragments(List.of());
        streamingModel.setCompleteTextOverride("   ");

        ChatContext context = streamingContext();
        ChatState next = replyNode.execute(context);

        assertThat(next).isEqualTo(ChatState.FALLBACK);
        assertThat(context.getErrorMessage()).contains("空回复");
    }

    /**
     * 回调始终不触发时必须靠等待上界脱身，而不是永久占住线程。
     *
     * <p>把超时配成 100ms，等待上界即 300ms，因此这条用例能在毫秒级验证"有上界"这件事。
     */
    @Test
    void neverFiringCallbackShouldTimeOut() {
        llmProperties.getChat().setTimeout(Duration.ofMillis(100));
        replyNode = new ReplyNode(syncModel, streamingModel, llmProperties, ragProperties);
        streamingModel.setNeverInvokeCallback(true);

        ChatContext context = streamingContext();
        ChatState next = replyNode.execute(context);

        assertThat(next).isEqualTo(ChatState.FALLBACK);
        assertThat(context.getErrorMessage()).contains("超时");
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造带流式回调的对话上下文。
     *
     * @return 对话上下文
     */
    private ChatContext streamingContext() {
        return ChatContext.builder()
                .sessionId("s-stream")
                .userMessage("七天无理由退货怎么操作")
                .streamListener(listener)
                .build();
    }

    /**
     * 记录回调内容的监听器。
     */
    private static class RecordingListener implements ChatStreamListener {

        private final List<String> fragments = new ArrayList<>();

        private final List<ChatState> states = new ArrayList<>();

        @Override
        public void onFragment(String fragment) {
            fragments.add(fragment);
        }

        @Override
        public void onState(ChatState state) {
            states.add(state);
        }
    }
}
