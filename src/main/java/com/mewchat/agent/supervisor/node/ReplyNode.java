package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.config.LlmProperties;
import com.mewchat.config.RagProperties;
import com.mewchat.rag.retrieval.RetrievedChunk;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ChatState#REPLY} 节点：生成最终回复文本。
 *
 * <p>把上下文里已经准备好的素材（知识片段、工具数据、会话摘要、最近对话）
 * 组装成提示词，交给大模型生成一段话。本节点<b>不做任何流程决策</b>，
 * 走到这里就意味着"回答已经确定可信，只需要把它说出来"。
 *
 * <p>提示词里反复强调"只依据给定资料回答、资料不足就明说"，
 * 因为客服场景最怕的不是答不上来，而是编造一个看起来合理的政策或时效。
 *
 * <p><b>两条生成路径，提示词只有一份</b>：上下文带流式回调时走
 * {@link StreamingChatModel} 逐字输出，否则走 {@link ChatModel} 一次性拿全文。
 * 两者共用同一个 {@link #buildMessages} —— 若各写一份提示词，
 * 流式与非流式的回答质量就会悄悄分叉，而这种差异极难被发现。
 *
 * <p><b>为什么需要把异步回调"桥"回同步</b>：流式模型的片段是通过回调交出来的
 * （在模型客户端的 IO 线程上），而状态机是同步推进的 —— 节点必须拿到最终结果
 * 才能决定返回 END 还是 FALLBACK。这里用 {@link CountDownLatch} 等待回调，
 * 把异步世界收敛回本项目的同步流程。
 *
 * <p>生成失败（模型限流、超时）不抛异常，而是返回 FALLBACK 让兜底节点接管，
 * 用户仍然能收到一句有意义的话。
 *
 * @author MewChat
 */
@Component
public class ReplyNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(ReplyNode.class);

    private static final String SYSTEM_PROMPT = """
            你是 MewChat 电商平台的在线客服。用中文、礼貌、简洁地回答用户问题。

            必须遵守：
            1. 只依据下方提供的【参考知识】与【业务数据】作答，
               不要编造平台政策、价格、时效、赔偿标准等任何未给出的信息。
            2. 如果资料不足以回答用户的问题，直接说明"这个我帮您转接人工客服确认"，
               不要猜测。
            3. 回答控制在 300 字以内。多条内容时用短句分点，不要长篇大论。
            4. 不要提及"参考知识""资料""上下文"这类内部概念，
               直接以客服的口吻把结论说给用户。
            5. 不要输出 markdown 标题、代码块等排版元素。
            """;

    private final ChatModel chatModel;

    private final StreamingChatModel streamingChatModel;

    private final LlmProperties llmProperties;

    private final RagProperties ragProperties;

    public ReplyNode(ChatModel chatModel,
                     StreamingChatModel streamingChatModel,
                     LlmProperties llmProperties,
                     RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.llmProperties = llmProperties;
        this.ragProperties = ragProperties;
    }

    @Override
    public ChatState state() {
        return ChatState.REPLY;
    }

    @Override
    public ChatState execute(ChatContext context) {
        context.setModelName(llmProperties.getChat().getModelName());

        try {
            if (context.getStreamListener() == null) {
                return generateSync(context);
            }
            return generateStreaming(context);
        } catch (Exception e) {
            // 生成失败降级为兜底：用户收到的是一句得体的转人工提示，而不是 500
            log.error("回复生成失败：session={}", context.getSessionId(), e);
            context.setErrorMessage("回复生成失败：" + e.getMessage());
            return ChatState.FALLBACK;
        }
    }

    /* ==================== 同步生成 ==================== */

    /**
     * 一次性生成完整回复（非流式调用）。
     *
     * @param context 对话上下文
     * @return 下一个状态
     */
    private ChatState generateSync(ChatContext context) {
        ChatResponse response = chatModel.chat(buildMessages(context));
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        return acceptText(context, text, response.tokenUsage());
    }

    /* ==================== 流式生成 ==================== */

    /**
     * 逐字生成回复（流式调用）。
     *
     * <p><b>已经推给用户的片段收不回来</b>：如果在推了几个字之后失败，
     * 用户屏幕上已经有半句话了。此时仍然选择降级为兜底（返回 FALLBACK），
     * 因为把半截回答伪装成完整回答、让用户以为"这就是全部"更糟。
     * 代价是前端会先看到半句、再看到兜底话术，因此约定：
     * <b>前端必须以 {@code done} 事件里的最终内容为准</b>，它是权威结果，
     * 收到后应当用它替换（而不是追加到）当前气泡。
     *
     * @param context 对话上下文
     * @return 下一个状态
     */
    private ChatState generateStreaming(ChatContext context) {
        ChatStreamListener listener = context.getStreamListener();

        // 累积实际推出去的文本。它同时是"用户看到了什么"的唯一记录
        StringBuilder delivered = new StringBuilder();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        streamingChatModel.chat(buildMessages(context), new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partial) {
                if (!StringUtils.hasText(partial)) {
                    // 兼容端点偶尔会推空片段，推给前端只会产生一次无意义的网络往返
                    return;
                }
                delivered.append(partial);
                listener.onFragment(partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.set(response);
                finished.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
                finished.countDown();
            }
        });

        if (!awaitCompletion(finished, context)) {
            return ChatState.FALLBACK;
        }

        Throwable error = failure.get();
        if (error != null) {
            log.error("流式生成失败：session={} 已推送 {} 字",
                    context.getSessionId(), delivered.length(), error);
            context.setErrorMessage("流式生成失败：" + error.getMessage());
            return ChatState.FALLBACK;
        }

        ChatResponse response = completed.get();
        if (response == null) {
            log.error("流式生成结束但未拿到完整响应：session={}", context.getSessionId());
            context.setErrorMessage("流式生成未返回完整响应");
            return ChatState.FALLBACK;
        }

        return acceptText(context, resolveStreamedText(delivered, response), response.tokenUsage());
    }

    /**
     * 判定流式生成最终采信哪段文本。
     *
     * <p>优先采信<b>实际推送给用户的片段拼接结果</b>：它才是用户屏幕上已有的内容。
     * 部分兼容端点在 {@code onCompleteResponse} 里给的是另一份（有时是被截断或
     * 去掉了首尾空白的）文本，若采信它，落库的回复就会与用户看到的不一致 ——
     * 刷新页面后"回答变了"，是很难解释的问题。两者不一致时记警告，便于发现厂商差异。
     *
     * @param delivered 已推送片段的拼接结果
     * @param response  完整响应
     * @return 最终采信的文本
     */
    private String resolveStreamedText(StringBuilder delivered, ChatResponse response) {
        String streamed = delivered.toString();
        String fromResponse = response.aiMessage() == null ? null : response.aiMessage().text();

        if (!StringUtils.hasText(streamed)) {
            // 一个片段都没收到，只能用完整响应兜底（例如端点只回调 onCompleteResponse）
            return fromResponse;
        }
        if (StringUtils.hasText(fromResponse) && !streamed.trim().equals(fromResponse.trim())) {
            log.warn("流式片段与完整响应不一致，采信已推送的片段：片段 {} 字，响应 {} 字",
                    streamed.length(), fromResponse.length());
        }
        return streamed;
    }

    /**
     * 等待流式回调结束。
     *
     * <p><b>为什么必须有等待上界</b>：模型客户端的超时只能保证"它自己会回调"，
     * 一旦客户端实现有缺陷、回调始终不来，这里就会永久占住一条 SSE 线程，
     * 并发一上来线程池即被抽干。有上界至少能把它变成一次可观测的失败。
     *
     * <p>上界的具体取值只在一处定义：见 {@link #streamWaitTimeout()} ——
     * 本方法不重复描述数值，避免调参后注释与代码分叉。
     *
     * @param finished 完成信号
     * @param context  对话上下文
     * @return true 表示回调已结束
     */
    private boolean awaitCompletion(CountDownLatch finished, ChatContext context) {
        long waitMillis = streamWaitTimeout().toMillis();
        try {
            if (finished.await(waitMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
            log.error("流式生成等待超时（{}ms）：session={}", waitMillis, context.getSessionId());
            context.setErrorMessage("流式生成等待超时");
            return false;
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉它会让上层容器无法感知停机/取消，线程也无法正确退出
            Thread.currentThread().interrupt();
            log.warn("流式生成被中断：session={}", context.getSessionId());
            context.setErrorMessage("流式生成被中断");
            return false;
        }
    }

    /**
     * 等待流式回调的上界。
     *
     * <p><b>为什么必须有上界</b>：模型客户端的超时只能保证"它自己会回调"，
     * 一旦客户端实现有缺陷、回调始终不来，这里就会永久占住一条 SSE 线程，
     * 并发一上来线程池即被抽干。有上界至少能把它变成一次可观测的失败。
     *
     * <p>取配置超时的三倍：流式回答的耗时天然高于同步请求（首字节 + 逐字输出），
     * 用同一个超时值会把正常的长回答掐断；但也不该是个凭感觉的常数，
     * 按配置倍数放大才能跟着 timeout 一起调整。
     *
     * @return 等待时长
     */
    private Duration streamWaitTimeout() {
        Duration configured = llmProperties.getChat().getTimeout();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            // 没配置超时时给一个足够宽的量级，仍不至于永久等待
            return Duration.ofMinutes(5);
        }
        return configured.multipliedBy(3);
    }

    /* ==================== 结果处理 ==================== */

    /**
     * 校验并接收生成的文本。
     *
     * @param context 对话上下文
     * @param text    生成文本
     * @param usage   token 用量，可为 null
     * @return 下一个状态
     */
    private ChatState acceptText(ChatContext context, String text, TokenUsage usage) {
        if (!StringUtils.hasText(text)) {
            log.warn("模型返回空回复：session={}", context.getSessionId());
            context.setErrorMessage("模型返回空回复");
            return ChatState.FALLBACK;
        }

        context.setReplyText(text.trim());
        fillTokenUsage(context, usage);
        log.debug("回复生成完成：session={} 长度={} tokens={}",
                context.getSessionId(), text.length(), context.getTotalTokens());
        return ChatState.END;
    }

    /**
     * 组装提示词。
     *
     * @param context 对话上下文
     * @return 消息列表
     */
    private List<ChatMessage> buildMessages(ChatContext context) {
        StringBuilder userPrompt = new StringBuilder();

        if (StringUtils.hasText(context.getSummary())) {
            userPrompt.append("【会话摘要】\n").append(context.getSummary()).append("\n\n");
        }

        if (!CollectionUtils.isEmpty(context.getRetrievedChunks())) {
            userPrompt.append("【参考知识】\n")
                    .append(formatChunks(context.getRetrievedChunks()))
                    .append('\n');
        }

        if (context.getToolResult() != null && StringUtils.hasText(context.getToolResult().getSummary())) {
            userPrompt.append("【业务数据】\n")
                    .append(context.getToolResult().getSummary()).append("\n\n");
        }

        userPrompt.append("【最近对话】\n").append(formatHistory(context));
        userPrompt.append("【用户问题】\n").append(context.effectiveQuery());

        return List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt.toString()));
    }

    /**
     * 格式化知识片段，附上来源与相关度。
     *
     * <p>单片段超长时截断：一段几万字的原文会把上下文占满，
     * 让模型忽略真正关键的其他片段。
     *
     * @param chunks 检索结果
     * @return 格式化文本
     */
    private String formatChunks(List<RetrievedChunk> chunks) {
        int maxChars = Math.max(100, ragProperties.getMaxChunkChars());
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            String text = chunk.getText() == null ? "" : chunk.getText();
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "…（已截断）";
            }
            builder.append('[').append(index++).append("] 来源：《")
                    .append(chunk.getDocTitle() == null ? "未知文档" : chunk.getDocTitle())
                    .append("》");
            if (chunk.getChunkNo() != null) {
                builder.append(" 第").append(chunk.getChunkNo()).append("段");
            }
            if (chunk.getScore() != null) {
                builder.append("（相关度 ").append(String.format("%.2f", chunk.getScore())).append('）');
            }
            builder.append('\n').append(text).append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 格式化最近对话。
     *
     * @param context 对话上下文
     * @return 格式化文本
     */
    private String formatHistory(ChatContext context) {
        if (CollectionUtils.isEmpty(context.getHistory())) {
            return "（无，这是首轮对话）\n";
        }
        List<String> lines = new ArrayList<>(context.getHistory().size());
        for (var turn : context.getHistory()) {
            lines.add((turn.isUser() ? "用户" : "客服") + "：" + turn.getContent());
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * 回填 token 用量。
     *
     * <p>用量可能为 null（部分兼容端点不返回），此时保持为空而不是填 0，
     * 以便后续统计时能区分"真的用了 0"和"没拿到数据"。
     *
     * @param context 对话上下文
     * @param usage   token 用量
     */
    private void fillTokenUsage(ChatContext context, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        context.setPromptTokens(usage.inputTokenCount());
        context.setCompletionTokens(usage.outputTokenCount());
        context.setTotalTokens(usage.totalTokenCount());
    }
}
