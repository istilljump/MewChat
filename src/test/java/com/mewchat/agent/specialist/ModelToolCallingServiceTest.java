package com.mewchat.agent.specialist;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.IntentType;
import com.mewchat.config.AgentProperties;
import com.mewchat.tool.ToolInvoker;
import com.mewchat.tool.order.OrderTool;
import com.mewchat.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModelToolCallingService} 的单元测试（不依赖数据库与真实模型）。
 *
 * <p>{@code ToolSpecialistTest} 已在"专家节点 ↔ 模型路径"的接缝上覆盖了
 * 主分支（选单工具 / 组合多工具 / 不选工具降级 / 超轮数降级 / 缺参数追问）；
 * 本类补的是服务自身的行为细节：坏参数、模型异常、无工具契约、开关语义。
 *
 * @author MewChat
 */
class ModelToolCallingServiceTest {

    /* ==================== 开关与装配 ==================== */

    /** 默认关闭：不改变既有行为是这个功能的前置承诺 */
    @Test
    void shouldBeDisabledByDefault() {
        assertThat(service(null, null, new AgentProperties()).enabled()).isFalse();
    }

    /** 工具层没有提供任何 @Tool 契约时无从谈起"让模型选工具"，降级返回 null */
    @Test
    void shouldReturnNullWhenNoToolContractAvailable() {
        AgentProperties properties = enabledProperties();
        ModelToolCallingService service = service(
                scriptedModel(textSummary("我来回答")),
                invokerReturning((toolName, params) -> ToolResult.ok(toolName, "事实", Map.of())),
                properties,
                /* contractObjects */ List.of());

        assertThat(service.run(context())).isNull();
    }

    /* ==================== 循环行为 ==================== */

    /**
     * 模型给出的参数不是合法 JSON 对象时，不能把异常抛给循环：
     * 解析失败收敛为一条失败的 ToolResult 回给模型，模型可以纠正或如实反馈。
     */
    @Test
    void shouldFeedParseFailureBackToModelInsteadOfThrowing() {
        AgentProperties properties = enabledProperties();
        ModelToolCallingService service = service(
                scriptedModel(
                        aiWithTools(toolCall(OrderTool.NAME, "这不是JSON")),
                        textSummary("参数有误。")),
                invokerReturning((toolName, params) -> ToolResult.ok(toolName, "不应被调用", Map.of())),
                properties);

        ModelToolCallingService.Outcome outcome = service.run(context());

        assertThat(outcome).isNotNull();
        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.results().get(0).isSuccess())
                .as("参数解析失败按工具失败处理，而不是让异常打断对话")
                .isFalse();
        assertThat(outcome.digest()).isEqualTo("参数有误。");
    }

    /** 模型调用失败的工具后仍给出结论：结果如实上交，由上层按"全部未查到"分流 */
    @Test
    void shouldCollectFailureResultsWithDigest() {
        AgentProperties properties = enabledProperties();
        ModelToolCallingService service = service(
                scriptedModel(
                        aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC999\"}")),
                        textSummary("没查到。")),
                invokerReturning((toolName, params) ->
                        ToolResult.notFound(toolName, "没查到订单，请核对")),
                enabledProperties());

        ModelToolCallingService.Outcome outcome = service.run(context());

        assertThat(outcome).isNotNull();
        assertThat(outcome.anySuccess()).isFalse();
        assertThat(outcome.clarifyCandidate().needsClarification())
                .as("notFound 携带追问话术，应能进入追问分支")
                .isTrue();
    }

    /** 模型端任何异常都只意味着"这条路没走通"，必须返回 null 让上层降级 */
    @Test
    void shouldReturnNullWhenChatModelThrows() {
        AgentProperties properties = enabledProperties();
        ChatModel brokenModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("模拟模型超时");
            }
        };
        ModelToolCallingService service = service(
                brokenModel,
                invokerReturning((toolName, params) -> ToolResult.ok(toolName, "事实", Map.of())),
                properties);

        assertThat(service.run(context())).isNull();
    }

    /**
     * 到轮数上限模型还在要求继续调工具时必须收敛：返回 null 降级，
     * 不能陪着模型无限烧 token。
     */
    @Test
    void shouldStopAtMaxRoundsAndReturnNull() {
        AgentProperties properties = enabledProperties();
        properties.setModelToolCallingMaxRounds(2);
        ModelToolCallingService service = service(
                scriptedModel(
                        aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}")),
                        aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}")),
                        aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}"))),
                invokerReturning((toolName, params) -> ToolResult.ok(toolName, "事实", Map.of())),
                properties);

        assertThat(service.run(context())).isNull();
    }

    /** 用户已断开时不应再发起任何模型调用：循环在第一轮前就退出并降级 */
    @Test
    void shouldNotCallModelWhenClientAlreadyDisconnected() {
        AgentProperties properties = enabledProperties();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ChatModel countingModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("不应到达")).build();
            }
        };
        com.mewchat.agent.ChatContext context = context();
        context.setStreamListener(new com.mewchat.agent.ChatStreamListener() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        });
        ModelToolCallingService service = service(
                countingModel,
                invokerReturning((toolName, params) -> ToolResult.ok(toolName, "事实", Map.of())),
                properties);

        assertThat(service.run(context)).isNull();
        assertThat(calls.get()).as("取消信号应在第一轮模型调用前生效").isZero();
    }

    /* ==================== 辅助 ==================== */

    private static AgentProperties enabledProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setModelToolCalling(true);
        return properties;
    }

    private static ModelToolCallingService service(ChatModel chatModel, ToolInvoker toolInvoker,
                                                   AgentProperties properties) {
        return service(chatModel, toolInvoker, properties, null);
    }

    /** contractObjects 为 null 时用真实工具类（一个带 @Tool 方法的对象）充当契约 */
    private static ModelToolCallingService service(ChatModel chatModel, ToolInvoker toolInvoker,
                                                   AgentProperties properties, List<Object> contractObjects) {
        Object contract = contractObjects == null ? new ContractStub() : null;
        List<Object> contracts = contractObjects == null ? List.of(contract) : contractObjects;
        ToolInvoker invokerWithContract = new ToolInvoker() {
            @Override
            public ToolResult invoke(String toolName, Map<String, Object> params) {
                return toolInvoker.invoke(toolName, params);
            }

            @Override
            public List<Object> toolContractObjects() {
                return contracts;
            }
        };
        return new ModelToolCallingService(providerOf(chatModel), providerOf(invokerWithContract), properties);
    }

    private static ChatContext context() {
        return ChatContext.builder()
                .sessionId("test-session")
                .userId(1L)
                .userMessage("我的订单到哪了")
                .intent(IntentType.ORDER_QUERY)
                .params(new HashMap<>())
                .build();
    }

    private static ChatModel scriptedModel(AiMessage... messages) {
        return new ChatModel() {
            private int index;

            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(messages[index++]).build();
            }
        };
    }

    private static AiMessage textSummary(String text) {
        return AiMessage.from(text);
    }

    private static AiMessage aiWithTools(ToolExecutionRequest... requests) {
        return AiMessage.from(requests);
    }

    private static ToolExecutionRequest toolCall(String toolName, String arguments) {
        return ToolExecutionRequest.builder()
                .id("req-" + toolName)
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    private static ToolInvoker invokerReturning(ToolInvoker delegate) {
        return delegate;
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return List.of(value).iterator();
            }
        };
    }

    /** 带 @Tool 方法的最小契约对象：服务只读它的规格，方法体永远不会被调用 */
    static class ContractStub {
        @dev.langchain4j.agent.tool.Tool("按订单号查询订单")
        public String queryOrder(String orderNo) {
            return "不应被调用";
        }
    }
}
