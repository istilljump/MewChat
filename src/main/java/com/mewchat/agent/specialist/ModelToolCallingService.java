package com.mewchat.agent.specialist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.config.AgentProperties;
import com.mewchat.tool.ToolInvoker;
import com.mewchat.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型自主选择工具（Function Calling）：让模型在工具调用循环里自己决定查什么。
 *
 * <p>与既有"意图 → 查表"路径的关系：<b>两者并存，模型路径失败即降级</b>。
 * 查表路径的能力边界是一次一意图一工具（"我的订单到哪了"只能查订单，
 * 说不到物流），模型路径放开了这个边界 —— 模型看到全部工具契约后可以
 * 连续调用多个工具，把组合事实带回来。但它引入了新的不确定性（模型可能
 * 不选工具、选错、陷入循环），因此本服务的契约是：
 * <ul>
 *     <li>模型没有调用任何工具、循环超轮数、或任何异常 → 返回 null，
 *         调用方（{@code ToolSpecialist}）<b>自动降级回查表路径</b></li>
 *     <li>只有真的调用过工具且模型给出了结论（digest）才产出结果</li>
 * </ul>
 * 也就是说，开启本服务后的最坏情形等于关闭它 —— 行为不劣化。
 *
 * <p><b>为什么不用 LangChain4j 的 AiServices</b>：AiServices 把"循环 + 执行 +
 * 上下文维护"封装成一个代理对象，但本项目的工具执行必须走自家的
 * {@link ToolInvoker}（ToolResult 语义、启动期校验、脱敏约定都在那条链上），
 * 且循环轮数要有显式上限。手动驱动 {@link ChatModel#chat(ChatRequest)} 的
 * 工具调用循环只有几十行，换来对每一环的控制权 ——
 * 与"流程由代码控制，模型只做被约束的决策"的项目原则一致。
 *
 * <p><b>工具执行的归属</b>：模型给出的只是"调用哪个工具、传什么参数"，
 * 真正的执行仍经由 {@link ToolInvoker#invoke}，返回的也是 {@link ToolResult} ——
 * 工具层对"谁在调它"一无所知，两条路径共享同一套工具语义。
 *
 * <p><b>产出的 digest 是给回复节点的素材，不是给用户的答案</b>：
 * 循环结束后模型会用自己的话总结工具返回的事实，这段话进入
 * {@code context.toolResult.summary()}，由回复节点按统一的回答提示词
 * 重新组织成最终回复（并保持流式）。不让模型在循环里直接对用户说话，
 * 是为了两条路径的回答质量不走样。
 *
 * @author MewChat
 */
@Component
public class ModelToolCallingService {

    private static final Logger log = LoggerFactory.getLogger(ModelToolCallingService.class);

    /**
     * 工具选择循环的系统提示词。
     *
     * <p>三条约束对应三个已知的失效模式：①"只能通过工具获取信息"防模型
     * 用自己的参数知识直接作答（那等于绕过了工具层的确定性数据）；
     * ②"调完就总结"防模型陷入无限续调；③"不知道就说不知道"防模型
     * 在工具查不到时编造事实 —— 工具查不到就该如实反馈，由上层决定追问还是兜底。
     */
    private static final String SYSTEM_PROMPT = """
            你是电商客服的查询助手。你的唯一任务是通过调用工具获取事实，然后把查到的事实如实汇总。
            规则：
            1. 只能依据工具返回的内容作答，禁止凭自己的知识编造订单、商品、物流或政策信息。
            2. 一次可以调用一个或多个工具；拿到所需事实后立即输出简短的事实汇总，不要继续调用工具。
            3. 工具查不到或提示缺少参数时，如实说明缺什么或没查到，不要猜测。
            4. 汇总用中文、面向客服同事阅读，只写事实本身，不要寒暄。""";

    /** 工具调用入参的 JSON 解析。用独立实例：接口层的序列化定制（Long→String）不该渗进工具参数 */
    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final ObjectProvider<ToolInvoker> toolInvokerProvider;

    private final AgentProperties agentProperties;

    public ModelToolCallingService(ObjectProvider<ChatModel> chatModelProvider,
                                   ObjectProvider<ToolInvoker> toolInvokerProvider,
                                   AgentProperties agentProperties) {
        this.chatModelProvider = chatModelProvider;
        this.toolInvokerProvider = toolInvokerProvider;
        this.agentProperties = agentProperties;
        // 功能开关写进启动日志：模型模式是否真的生效，从日志一眼可见，
        // 避免"配置没传到、以为开了其实没开"这类静默偏差
        log.info("模型自主选工具（Function Calling）：{}",
                agentProperties.isModelToolCalling() ? "已启用（失败自动降级为意图查表）" : "关闭（意图查表）");
    }

    /** 功能开关（来自 {@code mewchat.agent.model-tool-calling}）。 */
    public boolean enabled() {
        return agentProperties.isModelToolCalling();
    }

    /** 一次模型工具调用循环的产出：执行过的工具结果列表 + 模型的事实汇总。 */
    public record Outcome(List<ToolResult> results, String digest) {

        /** 是否至少有一个工具查到了数据 */
        public boolean anySuccess() {
            return results.stream().anyMatch(ToolResult::isSuccess);
        }

        /** 找一个适合走追问分支的结果（缺参数/未查到），没有则取最后一个 */
        public ToolResult clarifyCandidate() {
            return results.stream().filter(ToolResult::needsClarification).findFirst()
                    .orElse(results.get(results.size() - 1));
        }
    }

    /**
     * 运行工具调用循环。
     *
     * @param context 对话上下文（使用指代消解后的用户问题）
     * @return 循环产出；模型未调用任何工具、超轮数未收敛、或发生任何异常时返回 null，
     *         调用方应降级回"意图 → 查表"路径
     */
    public Outcome run(ChatContext context) {
        ToolInvoker toolInvoker = toolInvokerProvider.getIfAvailable();
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (toolInvoker == null || chatModel == null) {
            log.debug("模型选工具的前置条件不满足（toolInvoker={} chatModel={}），降级查表",
                    toolInvoker != null, chatModel != null);
            return null;
        }
        List<ToolSpecification> specifications = collectSpecifications(toolInvoker);
        if (specifications.isEmpty()) {
            log.debug("没有任何工具契约可交给模型，降级查表");
            return null;
        }

        try {
            return runLoop(context, chatModel, toolInvoker, specifications);
        } catch (Exception e) {
            // 模型路径的任何失败都只是"这条路没走通"，绝不能变成一次对话失败
            log.warn("模型自主选工具失败，将降级为意图查表路径：session={}",
                    context.getSessionId(), e);
            return null;
        }
    }

    private Outcome runLoop(ChatContext context, ChatModel chatModel, ToolInvoker toolInvoker,
                            List<ToolSpecification> specifications) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(context.effectiveQuery()));

        List<ToolResult> collected = new ArrayList<>();
        String digest = null;
        int maxRounds = Math.max(1, agentProperties.getModelToolCallingMaxRounds());
        ChatStreamListener listener = context.getStreamListener();

        for (int round = 0; round < maxRounds; round++) {
            // 用户已断开就不再发起下一轮模型调用（返回 null 由上层降级，
            // 而状态机循环会在下一个状态前中止整条流程）
            if (listener != null && listener.isCancelled()) {
                log.debug("用户已断开，提前结束工具调用循环：session={}", context.getSessionId());
                return null;
            }
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specifications)
                    .build());
            AiMessage aiMessage = response == null ? null : response.aiMessage();
            if (aiMessage == null) {
                break;
            }
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                // 模型给出最终文本：要么是事实汇总，要么是"没调工具直接作答"。
                // 是否采信由调用方根据 collected 是否为空判断。
                digest = aiMessage.text();
                break;
            }

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                ToolResult result = executeTool(toolInvoker, request);
                collected.add(result);
                messages.add(ToolExecutionResultMessage.from(request, describeForModel(result)));
            }
        }

        if (collected.isEmpty() || digest == null) {
            // 两种情形都降级：模型压根没选工具（digest 可能有值但那是凭空作答，不可信）；
            // 或到轮数上限还在要求继续调工具（模型没收敛，digest 也不存在）。
            log.debug("模型工具循环未产出可用结果（collected={} digest={}），降级查表：session={}",
                    collected.size(), digest != null, context.getSessionId());
            return null;
        }
        log.debug("模型自主选工具完成：session={} 工具调用={} 轮数上限={}",
                context.getSessionId(),
                collected.stream().map(ToolResult::getToolName).distinct().toList(),
                maxRounds);
        return new Outcome(List.copyOf(collected), digest);
    }

    /**
     * 执行模型要求的一次工具调用。
     *
     * <p>执行经由 {@link ToolInvoker#invoke}，与查表路径完全同链：
     * 工具名未注册、参数解析失败、工具抛异常都收敛为 {@link ToolResult}，
     * 并把结果原样回给模型（模型可以据此换参数重试或如实反馈）。
     */
    private ToolResult executeTool(ToolInvoker toolInvoker, ToolExecutionRequest request) {
        String toolName = request.name();
        Map<String, Object> params;
        try {
            String arguments = request.arguments();
            params = StringUtils.hasText(arguments)
                    ? ARGS_MAPPER.readValue(arguments, new TypeReference<Map<String, Object>>() { })
                    : Map.of();
        } catch (Exception e) {
            log.debug("模型给出的工具参数无法解析：tool={} arguments={}", toolName, request.arguments());
            return ToolResult.fail(toolName, "工具参数不是合法的 JSON 对象");
        }
        try {
            ToolResult result = toolInvoker.invoke(toolName, params);
            return result == null ? ToolResult.fail(toolName, "工具返回空结果") : result;
        } catch (Exception e) {
            log.warn("模型路径的工具调用异常：tool={}", toolName, e);
            return ToolResult.fail(toolName, "工具调用异常：" + e.getMessage());
        }
    }

    /**
     * 把 ToolResult 翻译成给模型看的文字。
     *
     * <p>成功给 summary（工具自己拟的自然语言事实）；缺参数/没查到给追问线索，
     * 模型可以据此换参数重试或如实向用户说明；失败给 errorMessage。
     */
    private String describeForModel(ToolResult result) {
        if (result.isSuccess()) {
            return result.getSummary();
        }
        if (result.needsClarification()) {
            if (StringUtils.hasText(result.getClarifyHint())) {
                return result.getClarifyHint();
            }
            return "缺少必要参数：" + String.join("、", result.getMissingParams());
        }
        return StringUtils.hasText(result.getErrorMessage())
                ? result.getErrorMessage() : "工具调用失败";
    }

    /**
     * 从注册表收集全部工具契约。
     *
     * <p>注册表在启动期已校验每个工具至少有一个 @Tool 方法，这里不会再遇到
     * "没有契约"的工具；收集结果为空只可能是工具层根本没装配。
     */
    private List<ToolSpecification> collectSpecifications(ToolInvoker toolInvoker) {
        Map<String, ToolSpecification> specifications = new LinkedHashMap<>();
        for (Object contractObject : toolInvoker.toolContractObjects()) {
            for (ToolSpecification specification : ToolSpecifications.toolSpecificationsFrom(contractObject)) {
                // 同名工具规格以先到者为准：与注册表"工具名唯一"的启动期校验一致
                specifications.putIfAbsent(specification.name(), specification);
            }
        }
        return List.copyOf(specifications.values());
    }
}
