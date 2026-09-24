package com.mewchat.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 确定性对话模型替身（测试专用）。
 *
 * <p>测试验证的是编排与检索逻辑，不是模型能力。用替身后每个分支的期望结果
 * 都是确定、可复现的，也不消耗 token。
 *
 * <p><b>为什么覆写 {@code chat(ChatRequest)} 而不覆写其它重载</b>：
 * {@code ChatModel} 的方法全部是 default 方法，最终都会汇聚到
 * {@code chat(ChatRequest)}。覆写这一个入口，业务代码无论调用哪个重载都能被拦截。
 * （曾用 Mockito 替身并 stub 了错误的重载，导致 default 方法返回 null、
 * 意图识别全部降级为 UNKNOWN —— 用真实实现类可以彻底避开这个陷阱。）
 *
 * <p>按系统提示词里的标识区分任务类型，因此提示词模板里那些标识性短语
 * （"意图识别模块""客服对话理解助手"等）是<b>测试与实现的隐式契约</b>，
 * 改动提示词措辞时需同步这里的判断条件。
 *
 * @author MewChat
 */
public class StubChatModel implements ChatModel {

    /** 默认的意图识别输出：知识问答 */
    public static final String DEFAULT_INTENT_JSON =
            "{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.92,"
                    + "\"rewrittenQuery\":\"七天无理由退换货规则\",\"params\":{}}";

    /** 指代消解的固定输出 */
    public static final String RESOLVED_TEXT = "七天无理由退换货规则这个政策有时间限制吗";

    /** 回复生成的固定输出 */
    public static final String REPLY_TEXT = "根据平台规则，签收后 7 天内可申请无理由退货。";

    /** 会话摘要的固定输出 */
    public static final String SUMMARY_TEXT = "用户咨询退换货规则，已告知七天无理由政策。";

    private String intentResponse = DEFAULT_INTENT_JSON;

    private String resolveResponse = RESOLVED_TEXT;

    private final List<String> capturedPrompts = new CopyOnWriteArrayList<>();

    /**
     * 重置为默认行为并清空捕获记录。
     */
    public void reset() {
        intentResponse = DEFAULT_INTENT_JSON;
        resolveResponse = RESOLVED_TEXT;
        capturedPrompts.clear();
    }

    /**
     * 设置意图识别的返回值，用于构造不同分支。
     *
     * @param intentResponse 模型应返回的原始文本（可以是合法 JSON，也可以是垃圾文本）
     */
    public void setIntentResponse(String intentResponse) {
        this.intentResponse = intentResponse;
    }

    /**
     * 设置指代消解的返回值。
     *
     * @param resolveResponse 消解结果
     */
    public void setResolveResponse(String resolveResponse) {
        this.resolveResponse = resolveResponse;
    }

    /**
     * 获取捕获到的全部提示词。
     *
     * @return 提示词快照
     */
    public List<String> getCapturedPrompts() {
        return new ArrayList<>(capturedPrompts);
    }

    /**
     * 清空提示词捕获记录。
     */
    public void clearCapturedPrompts() {
        capturedPrompts.clear();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String systemPrompt = SystemMessage.findAll(request.messages()).stream()
                .map(SystemMessage::text)
                .reduce("", (a, b) -> a + "\n" + b);
        capturedPrompts.add(String.valueOf(request.messages()));

        return ChatResponse.builder()
                .aiMessage(AiMessage.from(respond(systemPrompt)))
                .tokenUsage(new TokenUsage(120, 60, 180))
                .build();
    }

    /**
     * 按系统提示词的标识区分任务类型。
     *
     * <p>判断顺序有意义：意图识别的提示词里也含"指代"字样，
     * 因此必须先按更独特的标识判断。
     *
     * @param systemPrompt 系统提示词
     * @return 固定输出
     */
    private String respond(String systemPrompt) {
        if (systemPrompt.contains("意图识别模块")) {
            return intentResponse;
        }
        if (systemPrompt.contains("客服对话理解助手")) {
            return resolveResponse;
        }
        if (systemPrompt.contains("客服会话摘要助手")) {
            return SUMMARY_TEXT;
        }
        return REPLY_TEXT;
    }
}
