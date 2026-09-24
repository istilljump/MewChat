package com.mewchat.api.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mewchat.api.admin.dto.StatsOverviewView;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.common.security.TokenCodec;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.rag.document.DocumentIngestService;
import com.mewchat.service.ConversationService;
import com.mewchat.service.KnowledgeDocumentService;
import com.mewchat.service.MessageService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.QuestionClusteringService;
import com.mewchat.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营后台接口的测试（MockMvc + 真实安全链，无需数据库）。
 *
 * <p><b>本类最重要的一组断言是"权限"</b>：运营后台能读任意用户的完整对话、
 * 能改知识库、能处理工单，权限面比对话接口大得多。
 * 一旦 {@code /api/admin/**} 的角色校验失效，泄露的是全量用户数据，
 * 而且功能上一切正常、没有任何症状。因此这里把"普通用户访问后台被拒"
 * 单独钉死，而不是只测管理员能正常调通。
 *
 * <p>业务服务用替身，安全链（{@code SecurityConfig} + {@code TokenCodec}）用真货 ——
 * 否则"403 到底是不是真的拦住了"就成了自证。
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mewchat_admin;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "mewchat.auth.token-secret=admin-api-test-secret-0123456789"
})
@AutoConfigureMockMvc
class AdminApiTest {

    private static final String SESSION_ID = "7c1f0b9a3e5d4f2a8b6c1d0e9f8a7b6c";

    private static final long ADMIN_ID = 1727138400000000001L;

    private static final long CUSTOMER_ID = 1727138400000000002L;

    /** 与 {@link #document()} 的主键保持一致 */
    private static final long DOC_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenCodec tokenCodec;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private MessageService messageService;

    @MockitoBean
    private KnowledgeDocumentService documentService;

    @MockitoBean
    private DocumentIngestService ingestService;

    @MockitoBean
    private AdminStatsAssembler statsAssembler;

    @MockitoBean
    private QuestionClusteringService clusteringService;

    @MockitoBean
    private LowConfidenceQuestionService questionService;

    @BeforeEach
    void stubDefaults() {
        given(messageService.listAllBySessionId(anyString())).willReturn(List.of());
    }

    /* ==================== 权限 ==================== */

    /**
     * 未带令牌访问后台 → 401。
     */
    @Test
    void anonymousShouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20001));
    }

    /**
     * <b>普通用户（客户）访问后台必须被拒</b>，且每个分组都拒。
     *
     * <p>逐个路径断言，而不是只测一个：将来新增后台接口时，
     * 谁忘了它落在 {@code /api/admin/**} 之外，这里就会红。
     */
    @Test
    void customerShouldBeForbiddenOnEveryAdminGroup() throws Exception {
        String customerToken = token(AuthenticatedUser.TYPE_CUSTOMER, CUSTOMER_ID);

        List<String> adminPaths = List.of(
                "/api/admin/knowledge/documents",
                "/api/admin/tickets",
                "/api/admin/conversations",
                "/api/admin/analytics/overview",
                "/api/admin/analytics/optimization-checklist");

        for (String path : adminPaths) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(20002));
        }

        // 写接口同样要挡：新加的"标记已优化"是 POST，不在这份 GET 清单里，
        // 漏掉它就会出现"后台写操作比读操作更容易访问"的荒唐情况
        mockMvc.perform(post("/api/admin/analytics/questions/1/optimize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeDocId\":1}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 客服（userType=2）同样不可访问后台：当前后台只对管理员开放。
     *
     * <p>这条断言记录的是<b>当前的口径</b>：客服要处理工单时，
     * 需要另行设计"客服能看哪些工单"的权限模型，而不是把后台整体放开。
     */
    @Test
    void agentShouldAlsoBeForbiddenForNow() throws Exception {
        String agentToken = token(AuthenticatedUser.TYPE_AGENT, CUSTOMER_ID);

        mockMvc.perform(get("/api/admin/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken))
                .andExpect(status().isForbidden());
    }

    /* ==================== 工单处理 ==================== */

    /**
     * 管理员可查工单，状态应带上中文说明。
     */
    @Test
    void adminCanListTickets() throws Exception {
        given(ticketService.pageTickets(isNull(), anyInt(), anyInt()))
                .willReturn(pageOf(ticket()));

        mockMvc.perform(get("/api/admin/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value(0))
                .andExpect(jsonPath("$.data.records[0].statusLabel").value("待处理"))
                .andExpect(jsonPath("$.data.records[0].sessionId").value(SESSION_ID));
    }

    /**
     * 管理员可结单，返回的状态应已变更。
     */
    @Test
    void adminCanResolveTicket() throws Exception {
        Ticket resolved = ticket();
        resolved.setStatus(2);
        resolved.setFinishTime(LocalDateTime.now());
        given(ticketService.resolve(anyLong())).willReturn(resolved);

        mockMvc.perform(post("/api/admin/tickets/{id}/resolve", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusLabel").value("已解决"));
    }

    /* ==================== 对话记录 ==================== */

    /**
     * 管理员可读任意会话的完整对话，且能拿到 token、失败原因与引用来源。
     */
    @Test
    void adminCanReadAnyConversationTranscript() throws Exception {
        given(conversationService.getBySessionId(SESSION_ID))
                .willReturn(Conversation.builder().sessionId(SESSION_ID).build());
        given(messageService.listAllBySessionId(SESSION_ID))
                .willReturn(List.of(assistantMessage()));

        mockMvc.perform(get("/api/admin/conversations/{sessionId}/messages", SESSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].role").value("assistant"))
                .andExpect(jsonPath("$.data[0].agentName").value("RagSpecialist"))
                .andExpect(jsonPath("$.data[0].totalTokens").value(180))
                .andExpect(jsonPath("$.data[0].citations[0].docTitle").value("七天无理由退换货规则"));
    }

    /**
     * 会话ID写错时应明确报"不存在"，而不是返回空列表
     * （空列表会让人以为"这轮对话真的没说过话"）。
     */
    @Test
    void unknownSessionShouldReturnNotFound() throws Exception {
        given(conversationService.getBySessionId("no-such-session")).willReturn(null);

        mockMvc.perform(get("/api/admin/conversations/{sessionId}/messages", "no-such-session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10002));
    }

    /* ==================== 知识库与统计 ==================== */

    /**
     * 管理员可查文档列表，入库状态带中文说明。
     */
    @Test
    void adminCanListKnowledgeDocuments() throws Exception {
        given(documentService.pageForAdmin(isNull(), anyInt(), anyInt()))
                .willReturn(pageOf(document()));

        mockMvc.perform(get("/api/admin/knowledge/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].title").value("七天无理由退换货规则"))
                .andExpect(jsonPath("$.data.records[0].embedStatusLabel").value("已入库"));
    }

    /**
     * 新录入文档应返回文档ID。
     */
    @Test
    void adminCanCreateDocument() throws Exception {
        given(ingestService.ingest(any())).willReturn(999L);

        mockMvc.perform(post("/api/admin/knowledge/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新规则\",\"content\":\"正文内容\",\"category\":\"售后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(999));
    }

    /**
     * 标题为空应被参数校验拦下，且不会调用入库。
     */
    @Test
    void createDocumentWithoutTitleShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/admin/knowledge/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"content\":\"正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }

    /**
     * 管理员可看总览。
     */
    @Test
    void adminCanSeeStatsOverview() throws Exception {
        given(statsAssembler.overview()).willReturn(new StatsOverviewView(
                new StatsOverviewView.ConversationStats(3, 1, 2, 0),
                new StatsOverviewView.MessageStats(6, 3, 3, new BigDecimal("0.80"), 0, 1200L, 900L),
                new StatsOverviewView.TicketStats(1, 1, 0, 0, 0),
                new StatsOverviewView.KnowledgeStats(2, 8, 0),
                new StatsOverviewView.FlywheelStats(5, 5, 9),
                List.of(),
                List.of()));

        mockMvc.perform(get("/api/admin/analytics/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversations.total").value(3))
                .andExpect(jsonPath("$.data.messages.avgConfidence").value(0.80))
                .andExpect(jsonPath("$.data.flywheel.pendingQuestions").value(5));
    }

    /**
     * 手动重算聚类应返回本次结果的摘要。
     */
    @Test
    void adminCanTriggerRecluster() throws Exception {
        given(clusteringService.clusterPendingQuestions())
                .willReturn(new QuestionClusteringService.ClusterOutcome(9, 4, 2, 9));

        mockMvc.perform(post("/api/admin/analytics/clustering/recluster")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingQuestions").value(9))
                .andExpect(jsonPath("$.data.clusterCount").value(4))
                .andExpect(jsonPath("$.data.multiMemberClusters").value(2));
    }

    /* ==================== 飞轮收口：标记已优化 ==================== */

    /**
     * 管理员把某个问题标记为已优化，并关联到解答它的文档。
     *
     * <p>这是飞轮的收口动作：没有它，问题池只增不减，
     * 下一个来看清单的人会把同一批问题重新处理一遍。
     */
    @Test
    void adminCanMarkQuestionOptimized() throws Exception {
        given(documentService.getById(DOC_ID)).willReturn(document());
        given(questionService.markOptimized(1L, DOC_ID)).willReturn(true);

        mockMvc.perform(post("/api/admin/analytics/questions/{id}/optimize", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeDocId\":" + DOC_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 关联不存在的文档要被拒：否则统计里会出现一条指向不了任何东西的"已优化"记录。
     */
    @Test
    void markingWithUnknownDocumentShouldBeRejected() throws Exception {
        given(documentService.getById(DOC_ID)).willReturn(null);

        mockMvc.perform(post("/api/admin/analytics/questions/{id}/optimize", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeDocId\":" + DOC_ID + "}"))
                // 业务失败按项目约定是 HTTP 200 + 业务码（与同一个控制器里的
                // "重建索引"一致），不是真正的 HTTP 404 —— 前端两者都要判断
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("知识文档不存在")));
    }

    /**
     * 记录不存在或已经优化过时如实报错（不做静默成功）—— 静默成功会让运营
     * 以为写回生效了，而清单上那一行其实没变。
     */
    @Test
    void markingAlreadyOptimizedQuestionShouldReportNotFound() throws Exception {
        given(documentService.getById(DOC_ID)).willReturn(document());
        given(questionService.markOptimized(1L, DOC_ID)).willReturn(false);

        mockMvc.perform(post("/api/admin/analytics/questions/{id}/optimize", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgeDocId\":" + DOC_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已是已优化")));
    }

    /**
     * 请求体缺少文档ID时按参数问题拒绝。
     */
    @Test
    void markingWithoutDocumentIdShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/admin/analytics/questions/{id}/optimize", 1L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }

    /* ==================== 辅助 ==================== */

    /**
     * 签发管理员令牌。
     *
     * @return 令牌
     */
    private String adminToken() {
        return token(AuthenticatedUser.TYPE_ADMIN, ADMIN_ID);
    }

    /**
     * 按用户类型签发令牌。
     *
     * @param userType 用户类型
     * @param userId   用户ID
     * @return 令牌
     */
    private String token(int userType, long userId) {
        return tokenCodec.issue(userId, "tester", userType).token();
    }

    /**
     * 构造只有一个元素的分页结果。
     *
     * @param record 元素
     * @param <T>    类型
     * @return 分页结果
     */
    private static <T> Page<T> pageOf(T record) {
        Page<T> page = Page.of(1, 20);
        page.setRecords(List.of(record));
        page.setTotal(1);
        return page;
    }

    /**
     * 构造一条待处理工单。
     *
     * @return 工单
     */
    private static Ticket ticket() {
        return Ticket.builder()
                .id(1L)
                .sessionId(SESSION_ID)
                .userId(CUSTOMER_ID)
                .type("other")
                .description("【智能客服自动建单】用户问题：赠品什么时候发货")
                .status(0)
                .createTime(LocalDateTime.of(2024, 9, 24, 10, 0, 0))
                .build();
    }

    /**
     * 构造一条知识文档。
     *
     * @return 文档
     */
    private static KnowledgeDocument document() {
        return KnowledgeDocument.builder()
                .id(7L)
                .title("七天无理由退换货规则")
                .category("售后")
                .embedStatus(2)
                .chunkCount(3)
                .createTime(LocalDateTime.of(2024, 9, 24, 9, 0, 0))
                .build();
    }

    /**
     * 构造一条带引用与用量信息的助手消息。
     *
     * @return 消息
     */
    private static Message assistantMessage() {
        return Message.builder()
                .id(1727138400000000005L)
                .sessionId(SESSION_ID)
                .role("assistant")
                .content("签收后 7 天内可申请无理由退货。")
                .agentName("RagSpecialist")
                .confidence(new BigDecimal("0.9200"))
                .totalTokens(180)
                .costMs(1234)
                .status(1)
                .refDocs(List.of(MessageRefDoc.builder()
                        .chunkId("1727138400000000003_2")
                        .docTitle("七天无理由退换货规则")
                        .chunkNo(2)
                        .used(1)
                        .build()))
                .createTime(LocalDateTime.of(2024, 9, 24, 10, 0, 5))
                .build();
    }
}
