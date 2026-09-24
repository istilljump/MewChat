package com.mewchat.tool;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.agent.supervisor.ChatSupervisor;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.MessageService;
import com.mewchat.support.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务工具链路的端到端测试（需要真实 MySQL）。
 *
 * <p>验证场景二「多轮对话查订单 / 物流」：用户给出单号 → 编排层路由到工具 →
 * 工具查到业务数据 → <b>业务数据真的进了提示词</b> → 回复落库。
 * 其中"业务数据进了提示词"是这条链路唯一的关键断言 ——
 * 只断言"工具有返回值"是不够的，工具与回复节点之间还隔着
 * {@code ChatContext.toolResult} 这一跳，断了同样表现为"回答里没有订单信息"，
 * 而且不会报任何错。
 *
 * <p>工具用的是<b>真实实现</b>（{@code BusinessToolInvoker} + 三个业务工具），
 * 只有大模型被替换为确定性替身：这里要验的是编排与工具，不是模型能力。
 *
 * <p>本测试只覆盖确定性调用路径（意图识别抽取参数 → 按工具名调用），
 * 不覆盖"模型自主选择工具"的路径 —— 那条路径当前还没有接进编排。
 *
 * <p>默认不执行，需显式开启：
 * <pre>
 * ./mvnw test -Dmewchat.it.mysql=true \
 *     -Dmewchat.it.mysql.url="jdbc:mysql://127.0.0.1:3306/mewchat?useSSL=false&amp;allowPublicKeyRetrieval=true"
 * </pre>
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${mewchat.it.mysql.username:root}",
        "spring.datasource.password=${mewchat.it.mysql.password:}",
        "spring.datasource.url=${mewchat.it.mysql.url:jdbc:mysql://127.0.0.1:3306/mewchat"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        // 令牌密钥必须显式提供：application.yml 故意没有默认值（见该处注释）
        "mewchat.auth.token-secret=it-test-secret-0123456789abcdef"
})
@EnabledIfSystemProperty(named = "mewchat.it.mysql", matches = "true")
@Transactional
class ToolCallIntegrationTest {

    /** 意图识别：查订单，带上了订单号 */
    private static final String INTENT_ORDER_QUERY_WITH_NO = """
            {"intent":"ORDER_QUERY","confidence":0.90,
             "rewrittenQuery":"查询订单 MC202409240001 的状态","params":{"orderNo":"MC202409240001"}}
            """;

    /** 意图识别：查订单，订单号是个不存在的号码 */
    private static final String INTENT_ORDER_QUERY_UNKNOWN_NO = """
            {"intent":"ORDER_QUERY","confidence":0.90,
             "rewrittenQuery":"查询订单 MC000000000000 的状态","params":{"orderNo":"MC000000000000"}}
            """;

    /** 意图识别：查物流，给的是运单号 */
    private static final String INTENT_LOGISTICS_BY_TRACKING_NO = """
            {"intent":"LOGISTICS_QUERY","confidence":0.90,
             "rewrittenQuery":"查询运单 SF1234567890 的物流","params":{"trackingNo":"SF1234567890"}}
            """;

    /** 意图识别：查物流，但用户什么单号都没给 */
    private static final String INTENT_LOGISTICS_WITHOUT_NO = """
            {"intent":"LOGISTICS_QUERY","confidence":0.90,
             "rewrittenQuery":"查询我的包裹到哪了","params":{}}
            """;

    /** 生成的回复 */
    private static final String REPLY_TEXT = StubChatModel.REPLY_TEXT;

    @Autowired
    private ChatSupervisor chatSupervisor;

    @Autowired
    private StubChatModel stubChatModel;

    @Autowired
    private MessageService messageService;

    @Autowired
    private LowConfidenceQuestionService lowConfidenceQuestionService;

    @BeforeEach
    void resetStub() {
        stubChatModel.reset();
    }

    /* ==================== 订单 ==================== */

    /**
     * 给了有效订单号时，应查到订单并生成回复，业务数据必须出现在提示词里。
     */
    @Test
    void orderQueryWithValidNoShouldReplyAndFeedBusinessDataToModel() {
        stubChatModel.setIntentResponse(INTENT_ORDER_QUERY_WITH_NO);
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "帮我查一下订单 MC202409240001");

        assertThat(reply.getIntent()).isEqualTo(IntentType.ORDER_QUERY);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(reply.getContent()).isEqualTo(REPLY_TEXT);
        assertThat(reply.isHandoffRequired()).isFalse();
        // 有效置信度取较小值：min(意图 0.90, 工具 1.00)
        assertThat(reply.getConfidence()).isEqualByComparingTo("0.90");
        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.RESUME_CHECK,
                ChatState.INTENT_RECOGNIZE,
                ChatState.ROUTE,
                ChatState.TOOL_CALL,
                ChatState.CONFIDENCE_CHECK,
                ChatState.REPLY,
                ChatState.END);

        // 关键断言：工具查到的业务数据必须真的进了提示词
        assertThat(stubChatModel.getCapturedPrompts())
                .as("回复节点的提示词里应包含工具查到的业务数据")
                .isNotEmpty()
                .anySatisfy(prompt -> assertThat(prompt)
                        .contains("业务数据")
                        .contains("无线蓝牙耳机 Pro")
                        .contains("已发货")
                        .contains("SF1234567890"))
                .noneSatisfy(prompt -> assertThat(prompt).contains("任务失败"));

        // 处理方应记录为工具专家，便于统计"哪类问题由谁处理"
        Message assistantMessage = messageService.listAllBySessionId(sessionId).get(1);
        assertThat(assistantMessage.getAgentName()).isEqualTo("ToolSpecialist");
        assertThat(assistantMessage.getContent()).isEqualTo(REPLY_TEXT);
        assertThat(assistantMessage.getStatus()).isEqualTo(1);
    }

    /**
     * 订单号查不到时应引导用户核对（追问），而不是转人工。
     *
     * <p>这条断言的价值在于把设计取舍钉死：用户把单号写错是输入问题，
     * 追问一句就能解决；若判为兜底，用户会被告知"系统暂时处理不了，建议转人工"，
     * 还会凭空多出一张人工工单。
     */
    @Test
    void orderQueryWithUnknownNoShouldClarifyInsteadOfFallback() {
        stubChatModel.setIntentResponse(INTENT_ORDER_QUERY_UNKNOWN_NO);
        String sessionId = newSessionId();
        String question = "帮我查一下订单 MC000000000000 " + UUID.randomUUID();

        ChatReply reply = chatSupervisor.process(sessionId, question);

        assertThat(reply.getFinalState()).isEqualTo(ChatState.CLARIFY);
        assertThat(reply.isClarifying()).isTrue();
        assertThat(reply.isFallback()).isFalse();
        assertThat(reply.isHandoffRequired()).isFalse();
        assertThat(reply.getContent())
                .as("追问话术应由工具给出，指明查的是哪个单号")
                .contains("MC000000000000")
                .contains("核对");
        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.RESUME_CHECK,
                ChatState.INTENT_RECOGNIZE,
                ChatState.ROUTE,
                ChatState.TOOL_CALL,
                ChatState.CONFIDENCE_CHECK,
                ChatState.CLARIFY,
                ChatState.END);

        // 只是单号写错，不是"没答上来"，因此不该进低置信度问题池
        LowConfidenceQuestion recorded = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getQuestion, question)
                .one();
        assertThat(recorded)
                .as("输入错误不应被当成知识缺口记进低置信度池")
                .isNull();
    }

    /* ==================== 物流 ==================== */

    /**
     * 给了运单号时应查到物流轨迹并生成回复。
     */
    @Test
    void logisticsQueryByTrackingNoShouldReplyWithTrace() {
        stubChatModel.setIntentResponse(INTENT_LOGISTICS_BY_TRACKING_NO);
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "顺丰 SF1234567890 到哪了");

        assertThat(reply.getIntent()).isEqualTo(IntentType.LOGISTICS_QUERY);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.RESUME_CHECK,
                ChatState.INTENT_RECOGNIZE,
                ChatState.ROUTE,
                ChatState.TOOL_CALL,
                ChatState.CONFIDENCE_CHECK,
                ChatState.REPLY,
                ChatState.END);

        assertThat(stubChatModel.getCapturedPrompts())
                .anySatisfy(prompt -> assertThat(prompt)
                        .contains("运输中")
                        .contains("杭州西湖集散中心")
                        .contains("2024-09-25"));
    }

    /**
     * 什么单号都没给时应追问运单号，且流程要真的经过工具节点
     * （物流的必需参数是"二者取一"，无法由意图声明表达，因此只能由工具发现）。
     */
    @Test
    void logisticsQueryWithoutIdentifierShouldAskForTrackingNo() {
        stubChatModel.setIntentResponse(INTENT_LOGISTICS_WITHOUT_NO);
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "我的包裹到哪了");

        assertThat(reply.getFinalState()).isEqualTo(ChatState.CLARIFY);
        assertThat(reply.getContent()).contains("运单号");
        assertThat(reply.getVisitedStates()).contains(ChatState.TOOL_CALL);
    }

    /**
     * 订单号也能查物流：用户手里通常只有订单号，运单号在面单上。
     *
     * <p>这条链路是工具之间的依赖（物流 → 订单），断掉的表现是
     * "只有订单号的用户永远查不到物流"，而日志里看不出任何异常。
     */
    @Test
    void logisticsQueryByOrderNoShouldResolveThroughOrder() {
        stubChatModel.setIntentResponse("""
                {"intent":"LOGISTICS_QUERY","confidence":0.90,
                 "rewrittenQuery":"查询订单 MC202409240001 的物流","params":{"orderNo":"MC202409240001"}}
                """);
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "订单 MC202409240001 的物流到哪了");

        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(stubChatModel.getCapturedPrompts())
                .anySatisfy(prompt -> assertThat(prompt)
                        .contains("SF1234567890")
                        .contains("运输中"));
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造一个不重复的会话 ID，避免用例之间互相干扰。
     *
     * @return 会话业务ID
     */
    private String newSessionId() {
        return "it-tool-" + UUID.randomUUID();
    }

    /**
     * 测试配置：用确定性替身替换大模型。
     *
     * <p>工具不替换 —— 本测试要验的正是真实工具在编排里的表现。
     */
    @TestConfiguration
    static class StubConfig {

        /**
         * 对话模型替身。
         *
         * <p>标记 {@code @Primary}：容器里还有 {@code AiModelConfig} 提供的真实模型，
         * 由本替身优先被注入。
         *
         * @return 替身模型
         */
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }
}
