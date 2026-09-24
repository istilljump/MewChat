package com.mewchat.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.agent.supervisor.ChatSupervisor;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.rag.document.DocumentIngestRequest;
import com.mewchat.rag.document.DocumentIngestService;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.service.ConversationService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.MessageService;
import com.mewchat.support.StubChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试：编排层 + 真实 RAG + 真实落库（需要真实 MySQL）。
 *
 * <p><b>它补上了什么</b>：其它测试各自只覆盖链路的一段 ——
 * {@code ChatSupervisorIntegrationTest} 用替身检索服务验证编排，
 * {@code RagServiceIntegrationTest} 单独验证检索。
 * 本测试让**真实的 {@code RagService} 接进编排链路**，验证场景一的验收点：
 * 「知识问答有出处」—— 回答要有依据，依据要能溯源，且引用要随消息一起落库。
 *
 * <p><b>大模型仍是替身</b>（{@code @Primary} 覆盖），但<b>检索不再替换</b>，
 * 因此这里跑的是真实的 BM25 召回 → RRF → 重排 → 置信度链路。
 *
 * <p><b>不加 {@code @Transactional}</b>：InnoDB 全文索引在事务提交时才更新，
 * 若测试整体回滚，刚入库的切片对 {@code MATCH ... AGAINST} 不可见，
 * 检索必然为空。因此数据真实提交，由 {@link #cleanUp()} 负责清理。
 * 会话记录用逻辑删除清理，会在库里留下 {@code deleted=1} 的行 ——
 * 对测试库无害，但请知悉。
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
class ChatWithRagIntegrationTest {

    @Autowired
    private ChatSupervisor chatSupervisor;

    @Autowired
    private DocumentIngestService ingestService;

    @Autowired
    private StubChatModel stubChatModel;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private LowConfidenceQuestionService lowConfidenceQuestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long docId;

    private String sessionId;

    /** 本用例专属检索词，保证只可能召回本用例写入的文档 */
    private String uniqueTerm;

    @BeforeEach
    void setUp() {
        uniqueTerm = "qy" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sessionId = "it-rag-" + UUID.randomUUID();
        stubChatModel.reset();
    }

    @AfterEach
    void cleanUp() {
        // 物理清理而不是走 ingestService.delete()：后者对文档是逻辑删除，
        // 会在开发库里留下 deleted=1 的残行。测试跑完不该留痕。
        if (docId != null) {
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE doc_id = ?", docId);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", docId);
        }
        if (sessionId != null) {
            messageService.remove(Wrappers.<Message>lambdaQuery().eq(Message::getSessionId, sessionId));
            lowConfidenceQuestionService.remove(Wrappers.<LowConfidenceQuestion>lambdaQuery()
                    .eq(LowConfidenceQuestion::getSessionId, sessionId));
            // 会话表有逻辑删除字段，走 service 会留下 deleted=1 的行，这里直接物理清掉
            jdbcTemplate.update("DELETE FROM conversation WHERE session_id = ?", sessionId);
        }
    }

    /**
     * 场景一：知识问答有出处。
     *
     * <p>验证一条完整链路：知识入库 → 用户提问 → 检索到依据 → 生成回复 →
     * 引用来源既返回给用户、也随消息落库。
     */
    @Test
    void knowledgeQuestionShouldBeAnsweredWithTraceableCitations() {
        String docTitle = "退换货规则-" + UUID.randomUUID();
        docId = ingestService.ingest(DocumentIngestRequest.builder()
                .title(docTitle)
                .category("退换货")
                .content(buildContent())
                .build());

        ChatReply reply = chatSupervisor.process(sessionId, uniqueTerm);

        // 1) 走到了回复分支，而不是兜底或追问
        assertThat(reply.getFinalState()).isEqualTo(ChatState.REPLY);
        assertThat(reply.getIntent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(reply.isHandoffRequired()).isFalse();
        assertThat(reply.getContent()).isEqualTo(StubChatModel.REPLY_TEXT);
        // 只断言流程"形状"，不逐项相等：置信度落在中档时会多出一次 RAG_RETRIEVE（补检索），
        // 而真实 BM25 的分数随语料变化，逐项相等会让这条用例时绿时红。
        // 需要逐项断言的地方在 ChatSupervisorIntegrationTest —— 那里检索是替身、
        // 置信度固定，轨迹才是确定的
        assertThat(reply.getVisitedStates())
                .startsWith(
                        ChatState.CONTEXT_LOAD,
                        ChatState.GUARD_CHECK,
                        ChatState.RESUME_CHECK,
                        ChatState.INTENT_RECOGNIZE,
                        ChatState.ROUTE,
                        ChatState.RAG_RETRIEVE,
                        ChatState.CONFIDENCE_CHECK)
                .endsWith(ChatState.REPLY, ChatState.END);

        // 2) 置信度来自真实检索（最高分 + 命中数量），且高到足以通过阈值
        assertThat(reply.getConfidence()).isGreaterThan(BigDecimal.ZERO);

        // 3) 回答带出处：文档ID、标题、段落号齐全
        assertThat(reply.getCitations()).isNotEmpty();
        RetrievedChunk citation = reply.getCitations().get(0);
        assertThat(citation.getDocId()).isEqualTo(docId);
        assertThat(citation.getDocTitle()).as("引用必须带文档标题，否则用户看不到出处").isEqualTo(docTitle);
        assertThat(citation.getChunkNo()).isPositive();
        assertThat(citation.getText()).contains(uniqueTerm);

        // 4) 引用来源随助手消息一起落库，供前端回看"这段回答依据什么"
        List<Message> messages = messageService.listAllBySessionId(sessionId);
        assertThat(messages).hasSize(2);
        Message assistantMessage = messages.get(1);
        assertThat(assistantMessage.getRole()).isEqualTo("assistant");
        assertThat(assistantMessage.getAgentName()).isEqualTo("RagSpecialist");
        assertThat(assistantMessage.getRefDocs()).isNotEmpty();

        MessageRefDoc refDoc = assistantMessage.getRefDocs().get(0);
        assertThat(refDoc.getDocId()).isEqualTo(docId);
        assertThat(refDoc.getDocTitle()).isEqualTo(docTitle);
        assertThat(refDoc.getChunkId()).isEqualTo(docId + "_" + citation.getChunkNo());
        // 检索有真实命中，本轮置信度不应为 0
        assertThat(assistantMessage.getConfidence()).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * 知识库中没有相关内容时应走兜底，且问题进入低置信度问题池 ——
     * 与上一个用例构成"答得出 / 答不出"的对照。
     */
    @Test
    void questionWithoutKnowledgeShouldFallback() {
        // 不入库任何文档，直接问一个库里不可能有的内容
        String absentTerm = "qz" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        ChatReply reply = chatSupervisor.process(sessionId, absentTerm);

        assertThat(reply.getFinalState()).isEqualTo(ChatState.FALLBACK);
        assertThat(reply.isHandoffRequired()).isTrue();
        assertThat(reply.getCitations()).isEmpty();
        assertThat(reply.getConfidence()).isEqualByComparingTo(BigDecimal.ZERO);

        LowConfidenceQuestion recorded = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getQuestion, absentTerm)
                .one();
        assertThat(recorded).as("答不出的问题应进低置信度问题池，供后续补知识").isNotNull();
    }

    /**
     * 测试配置：用确定性替身替换大模型。
     *
     * <p>这里<b>只替换模型、不替换检索服务</b>，让真实的 RAG 链路参与测试。
     */
    @TestConfiguration
    static class StubModelConfig {

        /**
         * @return 替身对话模型
         */
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    /**
     * 构造一段包含唯一检索词的正文。
     *
     * @return 文档正文
     */
    private String buildContent() {
        return "第一条规则：签收之日起七天内，商品完好可以申请无理由退货。\n"
                + "第二条规则：" + uniqueTerm + " 属于定制类商品，不支持无理由退货。\n"
                + "第三条规则：退货运费由责任方承担，非质量问题由买家承担。\n"
                + "第四条规则：退款将在审核通过后原路返回，一般一到三个工作日到账。\n";
    }
}
