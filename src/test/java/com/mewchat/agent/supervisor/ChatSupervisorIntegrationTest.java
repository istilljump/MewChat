package com.mewchat.agent.supervisor;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.rag.retrieval.RetrievalResult;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.rag.retrieval.KnowledgeRetriever;
import com.mewchat.service.ConversationService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.MessageService;
import com.mewchat.support.StubChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 编排链路的端到端测试（需要真实 MySQL）。
 *
 * <p><b>为什么用真实数据库而不是 H2</b>：这条链路要验证的不只是流程，
 * 还有"记忆真的读到了上一轮的对话""助手回复真的落库了、统计字段对不对"。
 * 用 H2 需要另写一份 H2 方言的建表脚本，那份脚本一旦与 MySQL 版产生偏差，
 * 测试就会在错误的前提上通过 —— 这种假绿灯比没有测试更危险。
 * 因此本测试与 {@code MysqlSchemaMappingTest} 共用同一份 {@code sql/01_schema.sql}。
 *
 * <p><b>大模型与检索服务都被替换为确定性替身</b>：这里验证的是编排逻辑本身，
 * 不是模型能力。用替身后每个分支的期望结果都是确定的、可复现的，也不消耗 token。
 * 替身检索服务让 RAG 的"命中 → 回复"成功路径也能被覆盖到，
 * 而不是只能测到"知识库为空 → 兜底"。
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
// 每个用例结束后回滚，不在库里留下测试数据
@Transactional
class ChatSupervisorIntegrationTest {

    /* ==================== 替身的固定输出 ==================== */

    /** 意图识别：知识问答 */
    private static final String INTENT_KNOWLEDGE_QA = """
            {"intent":"KNOWLEDGE_QA","confidence":0.92,
             "rewrittenQuery":"七天无理由退换货规则是什么","params":{}}
            """;

    /** 意图识别：查订单，但用户没给订单号 */
    private static final String INTENT_ORDER_QUERY = """
            {"intent":"ORDER_QUERY","confidence":0.90,
             "rewrittenQuery":"查询我的订单状态","params":{}}
            """;

    /** 意图识别：模型没按格式输出（用于验证降级） */
    private static final String INTENT_MALFORMED = "我不太确定您想问什么。";

    /** 指代消解结果 */
    private static final String RESOLVED_TEXT = StubChatModel.RESOLVED_TEXT;

    /** 生成的回复 */
    private static final String REPLY_TEXT = StubChatModel.REPLY_TEXT;

    /** 召回片段的得分，需高于配置的置信度阈值 0.60 */
    private static final double CHUNK_SCORE = 0.95;

    @Autowired
    private ChatSupervisor chatSupervisor;

    @Autowired
    private StubChatModel stubChatModel;

    @Autowired
    private StubKnowledgeRetriever stubRetriever;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private LowConfidenceQuestionService lowConfidenceQuestionService;

    @BeforeEach
    void resetStubs() {
        stubChatModel.reset();
        stubRetriever.reset();
    }

    /* ==================== 用例 ==================== */

    /**
     * 工具类意图缺参数时应追问，而不是拿空参数去查库。
     */
    @Test
    void orderQueryWithoutOrderNoShouldAskForIt() {
        stubChatModel.setIntentResponse(INTENT_ORDER_QUERY);
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "帮我查一下我的订单");

        assertThat(reply.getIntent()).isEqualTo(IntentType.ORDER_QUERY);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.CLARIFY);
        assertThat(reply.isClarifying()).isTrue();
        assertThat(reply.getContent()).contains("订单号");
        assertThat(reply.isHandoffRequired()).isFalse();
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
    }

    /**
     * 知识类意图命中知识库时，应生成回复并把引用来源一起落库。
     */
    @Test
    void knowledgeQaWithRetrievalShouldReplyAndPersistCitations() {
        stubRetriever.setChunks(List.of(sampleChunk()));
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "七天无理由退货怎么操作");

        assertThat(reply.getIntent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(reply.getContent()).isEqualTo(REPLY_TEXT);
        assertThat(reply.isHandoffRequired()).isFalse();
        // 有效置信度取两者较小值：min(意图 0.92, 召回 0.95)
        assertThat(reply.getConfidence()).isEqualByComparingTo("0.92");
        assertThat(reply.getCitations()).hasSize(1);
        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.RESUME_CHECK,
                ChatState.INTENT_RECOGNIZE,
                ChatState.ROUTE,
                ChatState.RAG_RETRIEVE,
                ChatState.CONFIDENCE_CHECK,
                ChatState.REPLY,
                ChatState.END);
        assertThat(reply.getTotalTokens()).isEqualTo(180);

        // 引用来源应随消息一起落库，供前端展示"参考来源"
        Message assistantMessage = messageService.listAllBySessionId(sessionId).get(1);
        assertThat(assistantMessage.getAgentName()).isEqualTo("RagSpecialist");
        assertThat(assistantMessage.getRefDocs()).hasSize(1);
        MessageRefDoc refDoc = assistantMessage.getRefDocs().get(0);
        assertThat(refDoc.getChunkId()).isEqualTo("1727138400000000001_3");
        assertThat(refDoc.getDocTitle()).isEqualTo("七天无理由退换货规则");
        assertThat(refDoc.getUsed()).isEqualTo(1);
    }

    /**
     * 知识类意图没有召回时应走兜底（建议转人工），并把问题写入低置信度问题池。
     */
    @Test
    void knowledgeQaWithoutRetrievalShouldFallbackAndRecordQuestion() {
        stubRetriever.setChunks(List.of());
        String question = "无召回测试问题 " + UUID.randomUUID();
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, question);

        assertThat(reply.getIntent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.FALLBACK);
        assertThat(reply.isFallback()).isTrue();
        assertThat(reply.isHandoffRequired()).isTrue();
        assertThat(reply.getCitations()).isEmpty();
        assertThat(reply.getConfidence()).isEqualByComparingTo("0");
        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.RESUME_CHECK,
                ChatState.INTENT_RECOGNIZE,
                ChatState.ROUTE,
                ChatState.RAG_RETRIEVE,
                ChatState.CONFIDENCE_CHECK,
                ChatState.FALLBACK,
                ChatState.END);

        // 兜底的问题必须进池，这是"发现问题 → 补知识"闭环的入口
        LowConfidenceQuestion recorded = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getQuestion, question)
                .one();
        assertThat(recorded).as("兜底问题应写入低置信度问题池").isNotNull();
        assertThat(recorded.getHitCount()).isEqualTo(1);
        assertThat(recorded.getConfidence()).isEqualByComparingTo("0");
        assertThat(recorded.getOptimized()).isZero();
    }

    /**
     * 模型输出不合规时必须降级为 UNKNOWN 并转追问，而不是抛异常。
     */
    @Test
    void malformedIntentOutputShouldDegradeToClarify() {
        stubChatModel.setIntentResponse(INTENT_MALFORMED);
        String sessionId = newSessionId();

        ChatReply reply = chatSupervisor.process(sessionId, "随便说点什么");

        assertThat(reply.getIntent()).isEqualTo(IntentType.UNKNOWN);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.CLARIFY);
        assertThat(reply.getContent()).isNotBlank();
        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.RESUME_CHECK,
                ChatState.INTENT_RECOGNIZE,
                ChatState.ROUTE,
                ChatState.CLARIFY,
                ChatState.END);
    }

    /**
     * 一轮对话应落库两条消息（用户 + 助手），并维护会话的冗余统计字段。
     */
    @Test
    void eachTurnShouldPersistMessagesAndUpdateConversation() {
        stubRetriever.setChunks(List.of(sampleChunk()));
        String sessionId = newSessionId();

        chatSupervisor.process(sessionId, "七天无理由退货怎么操作");

        List<Message> messages = messageService.listAllBySessionId(sessionId);
        assertThat(messages).hasSize(2);

        Message userMessage = messages.get(0);
        assertThat(userMessage.getRole()).isEqualTo("user");
        assertThat(userMessage.getContent()).isEqualTo("七天无理由退货怎么操作");
        assertThat(userMessage.getCreateTime()).isNotNull();

        Message assistantMessage = messages.get(1);
        assertThat(assistantMessage.getRole()).isEqualTo("assistant");
        assertThat(assistantMessage.getContent()).isEqualTo(REPLY_TEXT);
        assertThat(assistantMessage.getTotalTokens()).isEqualTo(180);
        assertThat(assistantMessage.getCostMs()).isNotNull();
        assertThat(assistantMessage.getStatus()).isEqualTo(1);

        Conversation conversation = conversationService.getBySessionId(sessionId);
        assertThat(conversation).isNotNull();
        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getLastMessageTime()).isNotNull();
    }

    /**
     * 第二轮对话必须带上第一轮的内容 —— 这是短时记忆是否真正生效的直接证据。
     */
    @Test
    void secondTurnShouldCarryPreviousTurnIntoPrompt() {
        stubRetriever.setChunks(List.of(sampleChunk()));
        String sessionId = newSessionId();
        String firstQuestion = "七天无理由退货怎么操作";

        chatSupervisor.process(sessionId, firstQuestion);
        stubChatModel.clearCapturedPrompts();

        chatSupervisor.process(sessionId, "那时间限制是多久");

        assertThat(stubChatModel.getCapturedPrompts())
                .as("第二轮提示词应包含第一轮对话，否则短时记忆没有生效")
                .isNotEmpty()
                .anySatisfy(prompt -> assertThat(prompt).contains(firstQuestion).contains(REPLY_TEXT));
    }

    /**
     * 指代消解结果应参与后续判断，而不是被丢掉。
     */
    @Test
    void referenceResolutionResultShouldBeUsedDownstream() {
        stubRetriever.setChunks(List.of(sampleChunk()));
        String sessionId = newSessionId();

        // 先来一轮，让历史非空（无历史时指代消解会被直接跳过）
        chatSupervisor.process(sessionId, "七天无理由退货怎么操作");
        stubChatModel.clearCapturedPrompts();

        chatSupervisor.process(sessionId, "那这个有时间限制吗");

        assertThat(stubChatModel.getCapturedPrompts())
                .as("指代消解结果应传给意图识别")
                .isNotEmpty()
                .anySatisfy(prompt -> assertThat(prompt).contains(RESOLVED_TEXT));
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造一个不重复的会话ID，避免用例之间互相干扰。
     *
     * @return 会话业务ID
     */
    private String newSessionId() {
        return "it-" + UUID.randomUUID();
    }

    /**
     * 构造一条示例召回片段。
     *
     * @return 知识切片
     */
    private RetrievedChunk sampleChunk() {
        return RetrievedChunk.builder()
                .chunkId("1727138400000000001_3")
                .docId(1727138400000000001L)
                .docTitle("七天无理由退换货规则")
                .chunkNo(3)
                .score(CHUNK_SCORE)
                .text("签收之日起 7 天内，商品完好可申请无理由退货。")
                .build();
    }

    /* ==================== 测试替身 ==================== */

    /**
     * 测试配置：用确定性替身替换大模型与检索服务。
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

        /**
         * 检索服务替身，让 RAG 的命中路径也能被测试覆盖。
         *
         * <p>标记 {@code @Primary}：{@code RagService} 也实现了
         * {@link KnowledgeRetriever}，不指定优先级会让 {@code ObjectProvider}
         * 在注入时因候选不唯一而报错。
         *
         * @return 替身检索服务
         */
        @Bean
        @Primary
        StubKnowledgeRetriever stubKnowledgeRetriever() {
            return new StubKnowledgeRetriever();
        }
    }

    /**
     * 检索服务替身：返回用例预设的片段与置信度，用于分别验证"命中"与"无召回"两条路径。
     *
     * <p>直接给出置信度而不是让编排层自己算：置信度算法属于 rag 层的职责，
     * 这里模拟的是"检索已经算好了"的契约，编排层的职责只是拿它与阈值比较。
     */
    static class StubKnowledgeRetriever implements KnowledgeRetriever {

        /** 有召回时的置信度，需高于配置阈值 0.60 才能走回复分支 */
        private static final BigDecimal HIT_CONFIDENCE = new BigDecimal("0.95");

        private List<RetrievedChunk> chunks = new ArrayList<>();

        void reset() {
            chunks = new ArrayList<>();
        }

        void setChunks(List<RetrievedChunk> chunks) {
            this.chunks = chunks;
        }

        @Override
        public RetrievalResult retrieve(String query, int topK) {
            if (chunks.isEmpty()) {
                return RetrievalResult.empty(query);
            }
            return RetrievalResult.builder()
                    .query(query)
                    .chunks(chunks)
                    .confidence(HIT_CONFIDENCE)
                    .vectorHitCount(chunks.size())
                    .bm25HitCount(chunks.size())
                    .build();
        }
    }
}
