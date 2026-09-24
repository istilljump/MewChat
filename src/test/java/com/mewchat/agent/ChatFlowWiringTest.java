package com.mewchat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.agent.memory.ChatMemoryService;
import com.mewchat.agent.specialist.RagSpecialist;
import com.mewchat.agent.specialist.ToolSpecialist;
import com.mewchat.agent.supervisor.ChatSupervisor;
import com.mewchat.agent.supervisor.IntentRecognizer;
import com.mewchat.agent.supervisor.node.ClarifyNode;
import com.mewchat.agent.supervisor.node.ConfidenceCheckNode;
import com.mewchat.agent.supervisor.node.ContextLoadNode;
import com.mewchat.agent.supervisor.node.FallbackNode;
import com.mewchat.agent.supervisor.node.GuardCheckNode;
import com.mewchat.agent.supervisor.node.IntentRecognizeNode;
import com.mewchat.agent.supervisor.node.RejectNode;
import com.mewchat.agent.supervisor.node.ReplyNode;
import com.mewchat.agent.supervisor.node.ResumeCheckNode;
import com.mewchat.agent.supervisor.node.RouteNode;
import com.mewchat.common.observability.LangfuseClient;
import com.mewchat.config.AgentProperties;
import com.mewchat.config.LlmProperties;
import com.mewchat.config.RagProperties;
import com.mewchat.rag.retrieval.KnowledgeRetriever;
import com.mewchat.rag.retrieval.RetrievalResult;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.service.ConversationService;
import com.mewchat.service.GuardrailService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.TicketService;
import com.mewchat.support.StubChatModel;
import com.mewchat.support.StubStreamingChatModel;
import com.mewchat.tool.ToolInvoker;
import com.mewchat.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 编排链路的"装配与走向"测试（纯单元测试，不依赖 Spring、不依赖数据库）。
 *
 * <p><b>为什么需要这个测试类</b>：状态机有两套断言，此前各自留了一个缺口 ——
 * {@code ChatStateTest} 只验转移表自身的性质，而真正把节点接进状态机跑的
 * 集成测试需要 MySQL（默认跳过）。于是"节点返回了转移表不允许的后继状态"
 * 这种错误两边都看不见，却会让<b>每一轮对话</b>都在第一步抛异常、直接降级为兜底
 * （实际发生过：{@code ContextLoadNode} 出口写成了 {@code INTENT_RECOGNIZE}，
 * 而转移表只允许到 {@code GUARD_CHECK}）。本类把节点、状态机、控制器之外的全部
 * 依赖换成替身，让"一轮对话到底走了哪些状态"能在默认的 {@code mvnw test} 里被断言。
 *
 * <p><b>为什么用 Mockito 替身而不是 H2</b>：这里要验的是编排决策，与持久化无关。
 * 用替身既不需要为测试再维护一份 H2 建表脚本（那份脚本与 MySQL 版一旦产生偏差，
 * 测试就会在错误的前提下通过），也让每条用例的期望完全确定。
 * "记忆真的写进库了"这类断言仍由需要 MySQL 的集成测试负责。
 *
 * @author MewChat
 */
class ChatFlowWiringTest {

    /** 知识问答的意图输出：置信度高于达标线 0.70 */
    private static final String INTENT_KNOWLEDGE_QA = """
            {"intent":"KNOWLEDGE_QA","confidence":0.92,
             "rewrittenQuery":"七天无理由退换货规则","params":{}}
            """;

    /**
     * 订单查询的意图输出：置信度 0.60，<b>低于达标线但高于兜底线</b>。
     * 用于验证"工具已经查到数据、却因为意图分不高而被送去兜底"这个缺陷不再出现。
     */
    private static final String INTENT_ORDER_QUERY_LOW_CONFIDENCE = """
            {"intent":"ORDER_QUERY","confidence":0.60,
             "rewrittenQuery":"查询订单 MC202409240001","params":{"orderNo":"MC202409240001"}}
            """;

    private StubChatModel chatModel;

    private ChatMemoryService chatMemoryService;

    private GuardrailService guardrailService;

    private ConversationService conversationService;

    private LowConfidenceQuestionService lowConfidenceQuestionService;

    private TicketService ticketService;

    private StubKnowledgeRetriever retriever;

    private StubToolInvoker toolInvoker;

    private ChatSupervisor supervisor;

    @BeforeEach
    void setUp() {
        chatModel = new StubChatModel();
        chatMemoryService = mock(ChatMemoryService.class);
        guardrailService = mock(GuardrailService.class);
        conversationService = mock(ConversationService.class);
        lowConfidenceQuestionService = mock(LowConfidenceQuestionService.class);
        ticketService = mock(TicketService.class);
        retriever = new StubKnowledgeRetriever();
        toolInvoker = new StubToolInvoker();

        // 默认：指代消解原样返回，护栏不命中，检索无召回
        given(chatMemoryService.resolveReferences(anyString(), anyString()))
                .willAnswer(invocation -> invocation.getArgument(1));
        given(guardrailService.match(anyString())).willReturn(Optional.empty());
        given(guardrailService.rejectReply()).willReturn("抱歉，这类内容我不能回答。");

        supervisor = new ChatSupervisor(
                buildNodes(),
                chatMemoryService,
                lowConfidenceQuestionService,
                ticketService,
                mock(LangfuseClient.class),
                new AgentProperties());
    }

    /* ==================== 用例 ==================== */

    /**
     * 知识问答命中知识库：必须走完整条链路直到生成回复。
     *
     * <p>这条用例是本类存在的主要理由。轨迹断言是"节点有没有被正确接线"的唯一证据 ——
     * 只看回复文本是看不出来的：状态机在第一步就抛异常时，
     * 用户收到的兜底话术同样是非空的一段话。
     */
    @Test
    void knowledgeHitShouldWalkThroughToReply() {
        chatModel.setIntentResponse(INTENT_KNOWLEDGE_QA);
        retriever.setChunks(List.of(sampleChunk()));

        ChatReply reply = supervisor.process(newSessionId(), 1L, "七天无理由退货怎么操作");

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
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(reply.getContent()).isEqualTo(StubChatModel.REPLY_TEXT);
        assertThat(reply.getCitations()).hasSize(1);
        assertThat(reply.isHandoffRequired()).isFalse();
    }

    /**
     * 命中安全护栏的输入必须走拒答，且<b>不能进低置信度池、不能建工单</b>。
     *
     * <p>拒答是策略决定（"这类内容我不回答"），不是能力不足（"我答不上来"）。
     * 若把违规提问当成知识盲区喂进补知识流程，那套数据飞轮就被污染了。
     */
    @Test
    void guardrailHitShouldRejectWithoutPollutingTheFlywheel() {
        given(guardrailService.match(anyString())).willReturn(Optional.of("刷单"));

        ChatReply reply = supervisor.process(newSessionId(), 1L, "帮我刷单");

        assertThat(reply.getVisitedStates()).containsExactly(
                ChatState.CONTEXT_LOAD,
                ChatState.GUARD_CHECK,
                ChatState.REJECT,
                ChatState.END);
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REJECT);
        assertThat(reply.isHandoffRequired()).isFalse();

        verify(lowConfidenceQuestionService, never()).record(anyString(), any(), anyString());
        verify(ticketService, never()).createFallbackTicket(anyString(), any(), anyString(), any());
    }

    /**
     * 工具已经查到数据时，即使意图置信度不高也必须回复，而不是兜底。
     *
     * <p>工具是确定性查询：查到就是查到。若因为意图分 0.60 低于达标线 0.70 就转兜底，
     * 用户会听到"系统处理不了、已转人工" —— 明明订单就在手上，
     * 同一个问题还会被写进低置信度池、凭空多出一张工单。
     */
    @Test
    void toolSuccessShouldReplyEvenWhenIntentConfidenceIsLow() {
        chatModel.setIntentResponse(INTENT_ORDER_QUERY_LOW_CONFIDENCE);
        toolInvoker.setResult(ToolResult.ok("order_query", "订单已发货", Map.of("orderNo", "MC202409240001")));

        ChatReply reply = supervisor.process(newSessionId(), 1L, "MC202409240001 到哪了");

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
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(reply.isHandoffRequired()).isFalse();

        verify(lowConfidenceQuestionService, never()).record(anyString(), any(), anyString());
        verify(ticketService, never()).createFallbackTicket(anyString(), any(), anyString(), any());
    }

    /**
     * 知识库确实没有依据时，仍应兜底：转人工、把问题写进低置信度池、建一张工单。
     *
     * <p>与上一条用例成对：修掉"查到却兜底"不能把兜底本身也一起修没了。
     */
    @Test
    void noRetrievalShouldStillFallbackAndRecordTheQuestion() {
        chatModel.setIntentResponse(INTENT_KNOWLEDGE_QA);
        retriever.setChunks(List.of());

        ChatReply reply = supervisor.process(newSessionId(), 1L, "无依据的问题");

        assertThat(reply.getFinalState()).isEqualTo(ChatState.FALLBACK);
        assertThat(reply.isHandoffRequired()).isTrue();
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

        verify(lowConfidenceQuestionService).record(anyString(), any(), anyString());
        verify(ticketService).createFallbackTicket(anyString(), anyLong(), anyString(), any());
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造全部真实节点，只把节点的外部依赖换成替身。
     *
     * <p>刻意用真实节点类而不是替身节点：本测试要验的正是"这些节点接进状态机后
     * 的走向对不对"，把节点也替掉就只剩下空转的状态机。
     *
     * @return 节点列表
     */
    private List<ChatNode> buildNodes() {
        return List.of(
                new ContextLoadNode(chatMemoryService),
                new GuardCheckNode(guardrailService),
                new ResumeCheckNode(conversationService, new AgentProperties()),
                new IntentRecognizeNode(new IntentRecognizer(chatModel, new ObjectMapper())),
                new RouteNode(),
                new RagSpecialist(providerOf(retriever), new RagProperties()),
                new ToolSpecialist(providerOf(toolInvoker), conversationService),
                new ConfidenceCheckNode(new AgentProperties()),
                new ClarifyNode(),
                new RejectNode(guardrailService),
                new ReplyNode(chatModel, new StubStreamingChatModel(), new LlmProperties(), new RagProperties()),
                new FallbackNode());
    }

    /**
     * 用一个固定实例包出 {@link ObjectProvider}。
     *
     * <p>必须写成匿名类：{@code ObjectProvider} 同时继承 {@code ObjectFactory} 与
     * {@code Iterable}，不是函数式接口，不能用 lambda 冒充。
     *
     * @param instance 要提供的实例，可为 null（表示服务不可用）
     * @param <T>      实例类型
     * @return 只提供该实例的 ObjectProvider
     */
    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    /**
     * 构造一个不重复的会话ID，避免用例之间互相干扰。
     *
     * @return 会话业务ID
     */
    private static String newSessionId() {
        return "wiring-" + UUID.randomUUID();
    }

    /**
     * 构造一条示例召回片段。
     *
     * @return 知识切片
     */
    private static RetrievedChunk sampleChunk() {
        return RetrievedChunk.builder()
                .chunkId("1727138400000000001_3")
                .docId(1727138400000000001L)
                .docTitle("七天无理由退换货规则")
                .chunkNo(3)
                .score(0.95)
                .text("签收之日起 7 天内，商品完好可申请无理由退货。")
                .build();
    }

    /**
     * 检索服务替身：返回用例预设的片段。
     *
     * <p>置信度直接给定，因为"怎么算置信度"是 rag 层的职责，本类验的是编排层的取用。
     */
    private static final class StubKnowledgeRetriever implements KnowledgeRetriever {

        private List<RetrievedChunk> chunks = List.of();

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
                    .confidence(new BigDecimal("0.95"))
                    .vectorHitCount(chunks.size())
                    .bm25HitCount(chunks.size())
                    .build();
        }
    }

    /**
     * 工具替身：返回用例预设的结果，顺带记下被调用的工具名。
     */
    private static final class StubToolInvoker implements ToolInvoker {

        private ToolResult result = ToolResult.fail("unknown", "用例未预设结果");

        void setResult(ToolResult result) {
            this.result = result;
        }

        @Override
        public ToolResult invoke(String toolName, Map<String, Object> params) {
            return result;
        }
    }
}
