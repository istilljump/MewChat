package com.mewchat.agent.specialist;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.config.AgentProperties;
import com.mewchat.dao.mysql.entity.PendingClarification;
import com.mewchat.service.ConversationService;
import com.mewchat.tool.BusinessToolInvoker;
import com.mewchat.tool.ToolInvoker;
import com.mewchat.tool.ToolResult;
import com.mewchat.tool.logistics.LogisticsTool;
import com.mewchat.tool.order.OrderTool;
import com.mewchat.tool.product.ProductTool;
import com.mewchat.tool.refund.RefundTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 工具专家的测试（不依赖数据库）。
 *
 * <p>这是"编排层 ↔ 工具层"的接缝测试：本节点是把意图变成工具调用的那一环，
 * 它出错的表现有三种 —— 该调的没调（用户明明给了单号却还被追问）、
 * 不该调的乱调（拿着空参数去查库，返回一个"查不到"的假答案）、
 * 以及追问时给不出候选（用户被问"请提供订单号"却想不起来是哪一个）。
 * 三种都在这里钉死。
 *
 * <p><b>用的是真实的工具注册表与真实工具</b>，只把数据库依赖替换掉
 * （会话挂起状态由 Mockito 替身承接）：手搓工具替身只能验证"本节点会调用接口"，
 * 而真正容易断的是工具名对不上、出参结构对不上这类装配问题，
 * 它们只有走真实注册表才暴露得出来。
 *
 * @author MewChat
 */
class ToolSpecialistTest {

    /** 只注册工具与注册表的最小 Spring 上下文，用于拿到真实的 ToolInvoker */
    private static AnnotationConfigApplicationContext toolContext;

    /** 记录调用过程的包装器，用于断言"某些情况下工具根本没被调用" */
    private RecordingToolInvoker recordingInvoker;

    /** 会话服务替身：只用于承接"挂起澄清状态"的写入 */
    private ConversationService conversationService;

    private ToolSpecialist specialist;

    @BeforeEach
    void setUp() {
        recordingInvoker = new RecordingToolInvoker(realInvoker());
        conversationService = mock(ConversationService.class);
        specialist = new ToolSpecialist(providerOf(recordingInvoker), conversationService,
                disabledModelCalling());
    }

    @AfterAll
    static void closeContext() {
        if (toolContext != null) {
            toolContext.close();
        }
    }

    /* ==================== 正常路径 ==================== */

    /**
     * 订单号齐全时应真的查到订单，并把结果与置信度写回上下文。
     */
    @Test
    void orderQueryWithOrderNoShouldCallToolAndWriteResult() {
        ChatContext context = orderContext("MC202409240001");

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(recordingInvoker.calls).containsExactly(OrderTool.NAME);
        assertThat(context.getHandlerAgent()).isEqualTo("ToolSpecialist");
        assertThat(context.getToolResult().isSuccess()).isTrue();
        assertThat(context.getToolResult().getSummary())
                .as("工具摘要就是回复节点喂给模型的那段业务数据")
                .contains("已发货")
                .contains("无线蓝牙耳机 Pro")
                .contains("499.00");
        assertThat(context.getClarificationHint())
                .as("查到了订单就不该再追问")
                .isNull();
        assertThat(context.getAnswerConfidence())
                .as("确定性查询：查到就是满分")
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * 只给订单号的物流查询要能走通 —— 由订单反查运单号。
     */
    @Test
    void logisticsQueryWithOrderNoShouldResolveThroughOrder() {
        ChatContext context = contextOf(IntentType.LOGISTICS_QUERY, Map.of("orderNo", "MC202409240001"));

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(recordingInvoker.calls).containsExactly(LogisticsTool.NAME);
        assertThat(context.getToolResult().isSuccess()).isTrue();
        assertThat(context.getToolResult().getSummary()).contains("运输中").contains("顺丰速运");
    }

    /* ==================== 输入不全：追问 + 列候选 ==================== */

    /**
     * 缺订单号时，追问要<b>列出候选并挂起状态</b>，而不是干问一句"请提供订单号"。
     *
     * <p>三件事都要断言：没有拿着空参数去查库、追问里带上了候选、挂起状态被写下来。
     * 挂起状态是关键的一环 —— 没有它，用户下一轮回的"1"就无从还原。
     */
    @Test
    void orderQueryWithoutOrderNoShouldOfferCandidatesAndSuspend() {
        ChatContext context = contextOf(IntentType.ORDER_QUERY, Map.of());

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(recordingInvoker.calls)
                .as("缺参数时不应发起工具调用")
                .isEmpty();
        assertThat(context.getToolResult().needsClarification()).isTrue();
        assertThat(context.getAnswerConfidence()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(context.getClarificationHint())
                .as("追问应当把候选列出来，并说明怎么选")
                .contains("请从下面的选项里选一个")
                .contains("1)")
                .contains("MC202409240001");

        PendingClarification pending = capturedPending();
        assertThat(pending.getIntent()).isEqualTo(IntentType.ORDER_QUERY.name());
        assertThat(pending.getMissingParam()).isEqualTo("orderNo");
        assertThat(pending.getQuestion()).isEqualTo("测试输入");
        assertThat(pending.getOptions())
                .as("候选值要落库，否则用户回的序号无从还原")
                .hasSize(4);
        assertThat(pending.getOptions().get(0).getValue()).isEqualTo("MC202409240001");
        assertThat(pending.getOptions().get(0).getLabel()).contains("无线蓝牙耳机 Pro");
    }

    /**
     * 物流缺少标识时同样列出候选；候选项要填的参数是 <b>orderNo</b> 而不是 trackingNo。
     *
     * <p>候选值来自订单（就是订单号），填进 trackingNo 是名不副实的；
     * 而物流工具本身接受订单号并自行反查运单号，所以填 orderNo 是安全的。
     */
    @Test
    void logisticsQueryWithoutIdentifierShouldOfferCandidatesForOrderNo() {
        ChatContext context = contextOf(IntentType.LOGISTICS_QUERY, Map.of());

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(context.getClarificationHint()).contains("请从下面的选项里选一个");

        PendingClarification pending = capturedPending();
        assertThat(pending.getIntent()).isEqualTo(IntentType.LOGISTICS_QUERY.name());
        assertThat(pending.getMissingParam())
                .as("候选值是订单号，该填的参数就是 orderNo")
                .isEqualTo("orderNo");
    }

    /**
     * 商品查询缺商品名时列出商品候选，且候选值要填的参数是 <b>productName</b>。
     *
     * <p>与订单候选的区别正在于参数名：订单候选填 orderNo、商品候选填 productName。
     * 参数名若靠编排层去猜（比如"缺的参数里没有 orderNo 就填第一个"），
     * 接第三个候选来源时就会填错 —— 用户选了第 1 项，值却被填进另一个参数，
     * 工具拿着它去查，结果"没找到"，而整条链路不报任何错。
     */
    @Test
    void productQueryWithoutNameShouldOfferCandidatesForProductName() {
        ChatContext context = contextOf(IntentType.PRODUCT_QUERY, Map.of());

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(recordingInvoker.calls).as("缺参数时不应发起工具调用").isEmpty();
        assertThat(context.getClarificationHint())
                .contains("请从下面的选项里选一个")
                .contains("无线蓝牙耳机 Pro");

        PendingClarification pending = capturedPending();
        assertThat(pending.getIntent()).isEqualTo(IntentType.PRODUCT_QUERY.name());
        assertThat(pending.getMissingParam())
                .as("候选值是商品名，该填的参数就是 productName")
                .isEqualTo(ProductTool.PARAM_PRODUCT_NAME);
        assertThat(pending.getOptions()).isNotEmpty();
    }

    /**
     * 候选要填的参数名<b>以工具声明的为准</b>，而不是编排层按缺失参数推断。
     *
     * <p>这里故意让替身工具声明一个"猜不出来"的参数名（missingParams 里是 productName，
     * 声明的是 phone）：若编排层还在自己推断，落库的就会是 productName，
     * 这条用例会失败。这样一旦有人把声明机制改回推断，测试立刻报警。
     */
    @Test
    void clarificationParamShouldComeFromToolDeclaration() {
        ToolInvoker declaringInvoker = new ToolInvoker() {
            @Override
            public ToolResult invoke(String toolName, Map<String, Object> params) {
                return ToolResult.ok(toolName, "不应被调用", Map.of());
            }

            @Override
            public List<com.mewchat.tool.ClarificationOption> listOptions(String toolName, Long userId) {
                return List.of(new com.mewchat.tool.ClarificationOption("v1", "候选项一"));
            }

            @Override
            public String clarificationParam(String toolName) {
                return "phone";
            }
        };
        ToolSpecialist declaring = new ToolSpecialist(providerOf(declaringInvoker), conversationService,
                disabledModelCalling());

        declaring.execute(contextOf(IntentType.PRODUCT_QUERY, Map.of()));

        assertThat(capturedPending().getMissingParam())
                .as("必须以工具声明的 phone 为准，而不是被推断成第一个缺失参数 productName")
                .isEqualTo("phone");
    }

    /**
     * 只为空白字符的订单号等同于没给，同样不许查库。
     */
    @Test
    void blankOrderNoShouldBeTreatedAsMissing() {
        Map<String, Object> params = new HashMap<>();
        params.put("orderNo", "   ");

        ChatContext context = contextOf(IntentType.ORDER_QUERY, params);
        specialist.execute(context);

        assertThat(recordingInvoker.calls).isEmpty();
        assertThat(context.getToolResult().needsClarification()).isTrue();
    }

    /* ==================== 输入有误：用工具拟的话术，且不挂起 ==================== */

    /**
     * 单号查不到时应引导用户核对（追问），而不是走兜底转人工。
     *
     * <p>这是刻意的设计取舍：把"您的单号可能不对"说成"系统暂时处理不了，建议转人工"，
     * 会让用户对系统失去信任、也凭空多出一张人工工单。
     *
     * <p>此时<b>不该挂起</b>：用户并没有"从候选里选一个"这回事，
     * 挂起只会让下一轮的任何数字输入被误续接。
     */
    @Test
    void unknownOrderNoShouldClarifyWithToolAuthoredHintAndNotSuspend() {
        ChatContext context = orderContext("MC000000000000");

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(context.getClarificationHint())
                .as("追问话术应由工具给出，而不是泛泛的'请提供订单号'")
                .contains("MC000000000000")
                .contains("核对");
        assertThat(context.getAnswerConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(context.getErrorMessage())
                .as("单号写错不是系统故障，不应留下错误标记")
                .isNull();
        verify(conversationService, never()).savePendingClarification(any(), any());
    }

    /* ==================== 降级路径 ==================== */

    /**
     * 工具服务不可用时应按调用失败处理并继续走流程，而不是抛异常。
     */
    @Test
    void unavailableToolServiceShouldDegradeNotThrow() {
        ToolSpecialist withoutTools = new ToolSpecialist(providerThrowing(), conversationService,
                disabledModelCalling());

        ChatContext context = orderContext("MC202409240001");
        ChatState next = withoutTools.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(context.getToolResult().isSuccess()).isFalse();
        assertThat(context.getAnswerConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 工具服务不可用时仍要能追问，且退化为纯文字追问（没有候选可列）。
     */
    @Test
    void unavailableToolServiceShouldStillAskForTheMissingParam() {
        ToolSpecialist withoutTools = new ToolSpecialist(providerThrowing(), conversationService,
                disabledModelCalling());

        ChatContext context = contextOf(IntentType.ORDER_QUERY, Map.of());
        withoutTools.execute(context);

        assertThat(context.getClarificationHint())
                .as("列不出候选时要退回干问一句，而不是给出一个空列表")
                .contains("订单号");
        verify(conversationService, never()).savePendingClarification(any(), any());
    }

    /**
     * 工具抛异常时同样要收敛成一次失败结果，不能让异常冒泡打断整轮对话。
     */
    @Test
    void throwingToolShouldBeCaughtAndDegrade() {
        ToolSpecialist withBrokenTool = new ToolSpecialist(
                providerOf((toolName, params) -> {
                    throw new IllegalStateException("模拟下游超时");
                }), conversationService, disabledModelCalling());

        ChatContext context = orderContext("MC202409240001");
        ChatState next = withBrokenTool.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(context.getErrorMessage()).contains("模拟下游超时");
        assertThat(context.getAnswerConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 路由到了本节点却没登记工具，属于编排配置遗漏，应记错误并按失败处理。
     */
    @Test
    void intentWithoutRegisteredToolShouldFail() {
        ChatContext context = contextOf(IntentType.UNKNOWN, Map.of());

        ChatState next = specialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(context.getErrorMessage()).contains("未配置对应工具");
        assertThat(recordingInvoker.calls).isEmpty();
    }

    /* ==================== 模型自主选工具（Function Calling） ==================== */

    /**
     * 模型可以不按意图路由选工具：意图是订单查询，但模型判断该先查商品也能成立。
     *
     * <p>这正是模型路径与查表路径的本质差异 —— 查表路径"意图决定工具"是硬编码的，
     * 模型路径由模型依据用户原话自行决定。断言的锚点是结果：工具真的被调了、
     * 查到的事实进了 ToolResult、置信度按确定性查询给满分。
     */
    @Test
    void modelModeShouldLetModelChooseTheTool() {
        ToolSpecialist modelSpecialist = specialistWithModel(
                response(aiWithTools(toolCall(ProductTool.NAME, "{\"productName\":\"耳机\"}"))),
                response(textSummary("商品已查到。")));
        ChatContext context = contextOf(IntentType.ORDER_QUERY, Map.of());

        ChatState next = modelSpecialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(recordingInvoker.calls).containsExactly(ProductTool.NAME);
        assertThat(context.getToolResult().isSuccess()).isTrue();
        assertThat(context.getToolResult().getToolName()).isEqualTo(ProductTool.NAME);
        assertThat(context.getToolResult().getSummary()).isEqualTo("商品已查到。");
        assertThat(context.getAnswerConfidence()).isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * 模型一轮里组合调用多个工具时，结果聚合为一条成功的 ToolResult。
     */
    @Test
    void modelModeShouldAggregateMultipleToolCalls() {
        ToolSpecialist modelSpecialist = specialistWithModel(
                response(aiWithTools(
                        toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}"),
                        toolCall(ProductTool.NAME, "{\"productName\":\"耳机\"}"))),
                response(textSummary("订单与商品都已查到。")));
        ChatContext context = contextOf(IntentType.ORDER_QUERY, Map.of());

        modelSpecialist.execute(context);

        assertThat(recordingInvoker.calls).contains(OrderTool.NAME, ProductTool.NAME);
        assertThat(context.getToolResult().isSuccess()).isTrue();
        assertThat(context.getToolResult().getToolName())
                .isEqualTo(OrderTool.NAME + "+" + ProductTool.NAME);
        assertThat(context.getToolResult().getSummary()).isEqualTo("订单与商品都已查到。");
    }

    /**
     * 模型不调用任何工具（直接凭空作答）时必须降级回查表路径：
     * 没有工具结果的"回答"不可信，宁可走确定的意图查表。
     */
    @Test
    void modelModeShouldFallBackToTableWhenModelSkipsTools() {
        ToolSpecialist modelSpecialist = specialistWithModel(
                response(textSummary("我觉得您的订单应该已经发货了。")));
        ChatContext context = orderContext("MC202409240001");

        ChatState next = modelSpecialist.execute(context);

        assertThat(next).isEqualTo(ChatState.CONFIDENCE_CHECK);
        assertThat(recordingInvoker.calls).as("应降级为查表路径").containsExactly(OrderTool.NAME);
        assertThat(context.getToolResult().isSuccess()).isTrue();
        assertThat(context.getToolResult().getSummary()).contains("已发货");
    }

    /**
     * 模型到轮数上限仍在要求继续调工具（没收敛）时，放弃模型路径降级查表。
     */
    @Test
    void modelModeShouldFallBackWhenRoundsExhausted() {
        ToolSpecialist modelSpecialist = specialistWithModel(
                response(aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}"))),
                response(aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}"))),
                response(aiWithTools(toolCall(OrderTool.NAME, "{\"orderNo\":\"MC202409240001\"}"))));
        ChatContext context = orderContext("MC202409240001");

        modelSpecialist.execute(context);

        assertThat(context.getToolResult().getToolName())
                .as("降级后走查表路径，工具名是意图映射的那个")
                .isEqualTo(OrderTool.NAME);
        assertThat(context.getToolResult().isSuccess()).isTrue();
    }

    /**
     * 模型路径查到"缺参数"时同样走追问 + 列候选（两条路径共享追问逻辑）。
     */
    @Test
    void modelModeShouldAskClarificationWhenToolNeedsMoreInfo() {
        ToolSpecialist modelSpecialist = specialistWithModel(
                response(aiWithTools(toolCall(OrderTool.NAME, "{}"))),
                response(textSummary("工具提示缺少订单号。")));
        ChatContext context = contextOf(IntentType.ORDER_QUERY, Map.of());

        modelSpecialist.execute(context);

        assertThat(context.getToolResult().needsClarification()).isTrue();
        assertThat(context.getClarificationHint())
                .as("追问要列出候选，而不是干问")
                .contains("MC202409240001");
        assertThat(capturedPending().getMissingParam())
                .as("候选项要填进订单工具自己声明的 orderNo")
                .isEqualTo("orderNo");
        assertThat(context.getAnswerConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造一个"模型模式已启用"的专家节点，模型按给定响应顺序回放。
     *
     * @param responses 模型响应序列（超出后重复最后一个）
     * @return 专家节点
     */
    private ToolSpecialist specialistWithModel(ChatResponse... responses) {
        AgentProperties properties = new AgentProperties();
        properties.setModelToolCalling(true);
        ModelToolCallingService service = new ModelToolCallingService(
                providerOfChatModel(scriptedModel(responses)),
                providerOf(recordingInvoker), properties);
        return new ToolSpecialist(providerOf(recordingInvoker), conversationService, service);
    }

    /** 关闭模型模式的默认服务：构造签名要求这个参数，但行为与旧版完全一致 */
    private static ModelToolCallingService disabledModelCalling() {
        return new ModelToolCallingService(null, null, new AgentProperties());
    }

    /** 按顺序回放响应的替身模型（超出序列后重复最后一个） */
    private static ChatModel scriptedModel(ChatResponse... responses) {
        return new ChatModel() {
            private int index;

            @Override
            public ChatResponse chat(ChatRequest request) {
                return responses[Math.min(index++, responses.length - 1)];
            }
        };
    }

    private static ObjectProvider<ChatModel> providerOfChatModel(ChatModel model) {
        return new ObjectProvider<>() {
            @Override
            public ChatModel getObject() {
                return model;
            }

            @Override
            public ChatModel getIfAvailable() {
                return model;
            }

            @Override
            public Iterator<ChatModel> iterator() {
                return List.of(model).iterator();
            }
        };
    }

    private static ChatResponse response(AiMessage aiMessage) {
        return ChatResponse.builder().aiMessage(aiMessage).build();
    }

    /** 纯文本响应（模型没调工具） */
    private static AiMessage textSummary(String text) {
        return AiMessage.from(text);
    }

    /** 构造一个模型发出的工具调用请求 */
    private static ToolExecutionRequest toolCall(String toolName, String arguments) {
        return ToolExecutionRequest.builder()
                .id("req-" + toolName + '-' + arguments.hashCode())
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    /** 携带工具调用请求的模型响应 */
    private static AiMessage aiWithTools(ToolExecutionRequest... requests) {
        return AiMessage.from(requests);
    }

    /* ==================== （原）辅助 ==================== */

    /**
     * 取出写入的挂起状态。
     *
     * @return 挂起状态
     */
    private PendingClarification capturedPending() {
        ArgumentCaptor<PendingClarification> captor = ArgumentCaptor.forClass(PendingClarification.class);
        verify(conversationService).savePendingClarification(eq("test-session"), captor.capture());
        return captor.getValue();
    }

    /**
     * 构造订单查询上下文。
     *
     * @param orderNo 订单号
     * @return 对话上下文
     */
    private ChatContext orderContext(String orderNo) {
        return contextOf(IntentType.ORDER_QUERY, Map.of("orderNo", orderNo));
    }

    /**
     * 构造指定意图与参数的对话上下文。
     *
     * <p>带上 {@link OrderTool#DEMO_OWNER_USER_ID} 作为当前用户：候选订单必须按归属过滤
     * （见 {@link OrderTool#listOptions(Long)}），不带用户身份就一个候选也列不出来，
     * "列候选"那两条用例便测不到真正要验的东西。
     * "换一个用户就列不出候选"由 {@code OrderToolTest} 单独覆盖。
     *
     * @param intent 意图
     * @param params 参数
     * @return 对话上下文
     */
    private ChatContext contextOf(IntentType intent, Map<String, Object> params) {
        return ChatContext.builder()
                .sessionId("test-session")
                .userId(OrderTool.DEMO_OWNER_USER_ID)
                .userMessage("测试输入")
                .intent(intent)
                .params(new HashMap<>(params))
                .build();
    }

    /**
     * 取真实工具注册表。首次调用时初始化最小 Spring 上下文并复用，避免每个用例都重启容器。
     *
     * @return 工具调用入口
     */
    private static synchronized ToolInvoker realInvoker() {
        if (toolContext == null) {
            toolContext = new AnnotationConfigApplicationContext();
            // 工具已改为经 service 取数：上下文里注册内存桩（与种子数据同一份值），
            // 保持"不依赖数据库"的测试前提
            toolContext.registerBean(com.mewchat.service.BusinessDataService.class,
                    com.mewchat.support.BusinessDataStub::of);
            toolContext.register(OrderTool.class, LogisticsTool.class, RefundTool.class,
                    ProductTool.class, BusinessToolInvoker.class);
            toolContext.refresh();
        }
        return toolContext.getBean(ToolInvoker.class);
    }

    /**
     * 用现成的对象构造 {@link ObjectProvider}。
     *
     * <p>写成匿名类而不是 lambda：{@code ObjectProvider} 不是函数式接口
     * （它同时继承 {@code ObjectFactory} 与 {@code Iterable}），无法用 lambda 实现。
     * 只覆写本节点真正会用到的方法，其余保持接口默认行为。
     *
     * @param invoker 工具调用入口
     * @return 提供者
     */
    private static ObjectProvider<ToolInvoker> providerOf(ToolInvoker invoker) {
        return new ObjectProvider<>() {

            @Override
            public ToolInvoker getObject() {
                return invoker;
            }

            @Override
            public ToolInvoker getIfAvailable() {
                return invoker;
            }

            @Override
            public Iterator<ToolInvoker> iterator() {
                return List.of(invoker).iterator();
            }
        };
    }

    /**
     * 构造一个"取不到 Bean"的提供者，模拟工具层未装配。
     *
     * <p>{@code getIfAvailable()} 返回 null 才是真实语义：
     * 容器里没有候选 Bean 时它就是返回 null，而不是抛异常。
     *
     * @return 提供者
     */
    private static ObjectProvider<ToolInvoker> providerThrowing() {
        return new ObjectProvider<>() {

            @Override
            public ToolInvoker getObject() {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(ToolInvoker.class);
            }

            @Override
            public ToolInvoker getIfAvailable() {
                return null;
            }

            @Override
            public Iterator<ToolInvoker> iterator() {
                return List.<ToolInvoker>of().iterator();
            }
        };
    }

    /**
     * 记录调用过程的工具入口包装器。
     */
    private static class RecordingToolInvoker implements ToolInvoker {

        private final ToolInvoker delegate;

        private final List<String> calls = new ArrayList<>();

        RecordingToolInvoker(ToolInvoker delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolResult invoke(String toolName, Map<String, Object> params) {
            calls.add(toolName);
            return delegate.invoke(toolName, params);
        }

        @Override
        public List<com.mewchat.tool.ClarificationOption> listOptions(String toolName, Long userId) {
            // 候选必须走真实实现：替身返回空列表会让"列候选"这条分支测不到
            return delegate.listOptions(toolName, userId);
        }

        @Override
        public String clarificationParam(String toolName) {
            // 这个也要转发：漏掉它会静默退化成"编排层自己推断参数名"，
            // 于是"物流候选该填 orderNo 而不是 trackingNo"这条就测不出来了
            // （替身返回 null → 退化分支 → 填成第一个缺失参数 trackingNo）
            return delegate.clarificationParam(toolName);
        }

        @Override
        public List<Object> toolContractObjects() {
            // 同理必须转发：模型选工具路径要从这里拿 @Tool 契约，
            // 漏掉它会让"模型模式"在测试里永远走降级分支而不是被测到
            return delegate.toolContractObjects();
        }
    }
}
