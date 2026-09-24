package com.mewchat.api.chat;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.agent.IntentType;
import com.mewchat.agent.supervisor.ChatSupervisor;
import com.mewchat.common.security.TokenCodec;
import com.mewchat.config.AuthProperties;
import static com.mewchat.common.security.AuthenticatedUser.TYPE_CUSTOMER;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.service.ConversationService;
import com.mewchat.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话接口的测试（MockMvc，无需数据库）。
 *
 * <p><b>为什么用 MockMvc 而不是起真实 HTTP 服务</b>：这里要验的是"接口契约"——
 * 鉴权是否真的挡住了未登录请求、序列化出来的字段对不对、SSE 的事件名与帧序是否是
 * 前端约定的那样。这些在进程内就能完整验证，且不需要监听端口、不依赖网络。
 *
 * <p><b>数据库与编排层被替换为替身</b>：本类不验证"回答对不对"（那是
 * {@code ChatSupervisorIntegrationTest} 与 {@code ReplyNodeStreamTest} 的职责），
 * 只验证"接口把请求交对了、把结果包装对了"。用替身后每条用例都是确定的。
 *
 * <p><b>安全链是真实的</b>：{@code SecurityConfig}、{@code TokenCodec} 都用真货，
 * 只有业务服务是替身 —— 否则"401 到底是不是真的拦住了"就变成了自证。
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mewchat_api;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "mewchat.auth.token-secret=api-test-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class ChatControllerTest {

    private static final String SESSION_ID = "7c1f0b9a3e5d4f2a8b6c1d0e9f8a7b6c";

    private static final long USER_ID = 1727138400000000001L;

    /** 另一名用户，用于验证"读写别人的会话会被拒绝" */
    private static final long OTHER_USER_ID = 1727138400000000002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenCodec tokenCodec;

    @MockitoBean
    private ChatSupervisor chatSupervisor;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private MessageService messageService;

    /**
     * 把流式任务线程池换成同步执行，替代品只在本用例内生效。
     *
     * <p><b>为什么必须换</b>：真实实现把 SSE 任务丢给线程池，于是"工作线程往响应里写事件"
     * 与"测试线程读响应体"是并发的。MockMvc 的响应对象<b>不是线程安全的</b>：
     * 边写边读会直接抛 {@code ConcurrentModificationException}，读到一半就断言
     * 则会得到空响应体或半截响应体 —— 本类曾因此偶发失败（单独跑总是通过，
     * 全量跑时才暴露，典型的线程争用）。
     *
     * <p>换成同步执行后，事件在 {@code send()} 返回前就已写好（此时 emitter 会把它们
     * 暂存到 earlySendAttempts，等异步请求初始化时统一 flush），
     * 于是"事件内容与顺序"能被确定性地断言，不再取决于线程调度。
     * 异步能力本身由 Spring 提供，不需要在这里重复验证；
     * 而"客户端断开后要归还异步请求"这条由 {@code SseStreamListenerTest} 用替身 emitter 验证。
     *
     * <p>替身的<b>类型必须与真实 Bean 相同</b>（{@code ThreadPoolTaskExecutor}）：
     * {@code SseConfig.configureAsyncSupport} 里直接引用了 {@code sseTaskExecutor()}，
     * 换成不兼容的类型会让 Spring 以"@Bean 方法被不兼容的实例覆盖"直接启动失败。
     */
    @MockitoBean(name = "sseTaskExecutor")
    private ThreadPoolTaskExecutor sseTaskExecutor;

    @BeforeEach
    void stubDefaults() {
        // 默认：没有历史消息。不 stub 的话 Mockito 返回 null，
        // 控制器在 stream() 上就会 NPE，测出来的失败原因与真实缺陷无关
        given(messageService.listAllBySessionId(anyString())).willReturn(List.of());
        // 流式任务当场执行完，去掉用例里的并发
        willAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).given(sseTaskExecutor).execute(any(Runnable.class));
    }

    /* ==================== 鉴权 ==================== */

    /**
     * 未携带令牌访问对话接口应被拒绝，且返回可解析的统一结构。
     *
     * <p>认证失败是 HTTP 401 + 业务码 20001：鉴权发生在 Spring Security 过滤器里，
     * 还没进入业务层，因此它给的是真正的 HTTP 状态码 ——
     * 与"业务失败返回 HTTP 200 + 业务码"的约定不同，前端两者都要判断。
     */
    @Test
    void requestWithoutTokenShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/chat/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20001))
                .andExpect(jsonPath("$.message").value("未登录或登录已过期，请重新登录"));
    }

    /**
     * 令牌被篡改、或由别的密钥签发，都必须被拒绝。
     *
     * <p>这条同时验证了过滤器确实在用 {@link TokenCodec} 校验签名，
     * 而不是"看到 Authorization 头就放行"。
     */
    @Test
    void requestWithForgedTokenShouldBeRejected() throws Exception {
        TokenCodec attacker = new TokenCodec(forgedProperties());
        String forged = attacker.issue(USER_ID, "attacker", TYPE_CUSTOMER).token();

        mockMvc.perform(post("/api/chat/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20001));

        mockMvc.perform(post("/api/chat/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken() + "tampered"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 携带合法令牌应能新建会话，返回的是服务端生成的会话ID。
     */
    @Test
    void createSessionWithValidTokenShouldReturnSessionId() throws Exception {
        given(conversationService.createForUser(USER_ID))
                .willReturn(Conversation.builder()
                        .sessionId(SESSION_ID)
                        .userId(USER_ID)
                        .build());

        mockMvc.perform(post("/api/chat/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(SESSION_ID));
    }

    /* ==================== 会话历史 ==================== */

    /**
     * 读取别人的会话必须被拒绝，且不透露该会话是否存在。
     *
     * <p>这里断言的是 HTTP 200 + 业务码 20002，而不是 HTTP 403：
     * 归属校验在业务层完成，抛出的 {@code BizException} 由全局异常处理器
     * 统一转成 Result（§四.6 的约定）。与"未带令牌"那条对照看 ——
     * 那条在 Security 过滤器里就被拦下，因此是真正的 HTTP 401。
     * 两种失败形态并存是既有设计的取舍，前端需要同时看状态码与业务码。
     */
    @Test
    void historyOfAnotherUsersSessionShouldBeRejected() throws Exception {
        // 归属校验不通过时服务层返回 null（不区分"不存在"与"不属于你"）
        given(conversationService.getOwnedBySessionId(SESSION_ID, OTHER_USER_ID)).willReturn(null);

        mockMvc.perform(get("/api/chat/session/{sessionId}/history", SESSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(OTHER_USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20002))
                .andExpect(jsonPath("$.message").value(ConversationService.SESSION_UNAVAILABLE_MESSAGE));
    }

    /**
     * 读取自己的会话应返回消息列表，并把引用来源映射成对外结构。
     */
    @Test
    void historyOfOwnSessionShouldReturnMessages() throws Exception {
        given(conversationService.getOwnedBySessionId(SESSION_ID, USER_ID))
                .willReturn(Conversation.builder().sessionId(SESSION_ID).userId(USER_ID).build());
        given(messageService.listAllBySessionId(SESSION_ID))
                .willReturn(List.of(userMessage(), assistantMessage()));

        mockMvc.perform(get("/api/chat/session/{sessionId}/history", SESSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                // 雪花ID必须序列化成字符串，否则前端按数字解析会丢精度
                .andExpect(jsonPath("$.data[0].id").value("1727138400000000001"))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[1].role").value("assistant"))
                .andExpect(jsonPath("$.data[1].agentName").value("RagSpecialist"))
                .andExpect(jsonPath("$.data[1].citations[0].docTitle").value("七天无理由退换货规则"))
                .andExpect(jsonPath("$.data[1].citations[0].chunkNo").value(2));
    }

    /* ==================== 流式对话 ==================== */

    /**
     * 一轮对话应以 SSE 事件流返回：会话ID → 状态 → 片段 → 结束。
     */
    @Test
    void sendShouldReturnSseEventStream() throws Exception {
        given(chatSupervisor.processStream(anyString(), anyLong(), anyString(), any(ChatStreamListener.class)))
                .willAnswer(invocation -> {
                    ChatStreamListener listener = invocation.getArgument(3);
                    // 替身只负责"像真实流程那样回调"，事件格式由 SSE 层决定
                    listener.onState(ChatState.RAG_RETRIEVE);
                    listener.onFragment("根据平台规则，");
                    listener.onFragment("签收后 7 天内可申请无理由退货。");
                    return ChatReply.builder()
                            .sessionId(SESSION_ID)
                            .content("根据平台规则，签收后 7 天内可申请无理由退货。")
                            .intent(IntentType.KNOWLEDGE_QA)
                            .confidence(new BigDecimal("0.92"))
                            .citations(List.of())
                            .finalState(ChatState.REPLY)
                            .visitedStates(List.of(ChatState.CONTEXT_LOAD, ChatState.REPLY))
                            .handoffRequired(false)
                            .build();
                });

        MvcResult result = mockMvc.perform(post("/api/chat/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"七天无理由退货怎么操作\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitSseBody(result);

        assertThat(body)
                .as("首帧应是会话ID，供前端确认续接的会话")
                .contains("event:session")
                .contains(SESSION_ID);
        assertThat(body)
                .as("状态事件用于显示进度提示")
                .contains("event:state")
                .contains(ChatState.RAG_RETRIEVE.getLabel());
        assertThat(body)
                .as("回复内容应逐片推送")
                .contains("event:message")
                .contains("签收后 7 天内可申请无理由退货。");
        assertThat(body)
                .as("done 事件携带权威结果：前端据此替换气泡，而不是依赖片段拼接")
                .contains("event:done")
                .contains("\"finalState\":\"REPLY\"")
                .contains("\"handoffRequired\":false");
    }

    /**
     * 未携带令牌的流式请求同样应被拒绝 —— <b>不能因为它是异步接口就绕过鉴权</b>。
     */
    @Test
    void sendWithoutTokenShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(20001));
    }

    /**
     * 参数不合法时返回统一结构，且不会发起对话。
     */
    @Test
    void sendWithBlankMessageShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }

    /**
     * 请求体不是合法 JSON（或编码不是 UTF-8）时，要报成参数问题而不是系统故障。
     *
     * <p>报成 500 + "系统繁忙"会误导两方：调用方以为是服务端故障而去重试，
     * 而真正该做的是检查请求体；服务端的错误率也被这些本可避免的 500 污染。
     * 现实中这一条最常见的触发方式是<b>终端把中文按 GBK 发出去</b>，
     * 因此提示里点明编码。
     */
    @Test
    void malformedBodyShouldBeReportedAsParameterProblem() throws Exception {
        byte[] notUtf8 = new byte[]{
                '{', '"', 's', 'e', 's', 's', 'i', 'o', 'n', 'I', 'd', '"', ':', '"', 'x', '"',
                ',', '"', 'm', 'e', 's', 's', 'a', 'g', 'e', '"', ':', '"', (byte) 0xb0, (byte) 0xa1, '"', '}'};

        mockMvc.perform(post("/api/chat/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notUtf8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UTF-8")));
    }

    /**
     * 追问与兜底的分支没有任何文本片段，此时必须补发一次完整内容，
     * 否则前端会渲染出一个空气泡。
     */
    @Test
    void sendWithFixedTextReplyShouldEmitContentAsMessage() throws Exception {
        given(chatSupervisor.processStream(anyString(), anyLong(), anyString(), any(ChatStreamListener.class)))
                .willReturn(ChatReply.builder()
                        .sessionId(SESSION_ID)
                        .content("好的，为了帮您查询，麻烦提供一下订单号。")
                        .intent(IntentType.ORDER_QUERY)
                        .confidence(BigDecimal.ZERO)
                        .citations(List.of())
                        .finalState(ChatState.CLARIFY)
                        .visitedStates(List.of(ChatState.CONTEXT_LOAD, ChatState.CLARIFY))
                        .handoffRequired(false)
                        .build());

        MvcResult result = mockMvc.perform(post("/api/chat/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"帮我查订单\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitSseBody(result);
        assertThat(body).contains("麻烦提供一下订单号").contains("event:done");
    }

    /* ==================== 辅助 ==================== */

    /**
     * 派发这一轮 SSE 请求，等事件流收尾之后再取响应体。
     *
     * <p>本轮 SSE 任务由同步执行器当场跑完（见 {@link #sseTaskExecutor}），因此这里读到的
     * 已经是完整的事件流。仍然等到 {@code done} 出现再断言，是为了让"读到空/半截响应体"
     * 这种失败不再可能以偶发形式出现：{@code done} 是事件流的最后一帧，
     * 它出现就意味着帧序、片段内容与权威结果都已写入。
     *
     * <p>等待有上界：真丢了 {@code done}（例如收尾不再下发权威结果）时，用例会在
     * 10 秒后失败，而不是把构建挂死。
     *
     * @param result 已 asyncStarted 的请求结果
     * @return SSE 响应体
     */
    private String awaitSseBody(MvcResult result) throws Exception {
        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();

        String body = dispatched.getResponse().getContentAsString();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!body.contains("event:done") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            body = dispatched.getResponse().getContentAsString();
        }
        return body;
    }

    /**
     * 用当前用户签发一个合法令牌。
     *
     * @return 令牌
     */
    private String validToken() {
        return tokenFor(USER_ID);
    }

    /**
     * 为指定用户签发令牌。
     *
     * @param userId 用户ID
     * @return 令牌
     */
    private String tokenFor(long userId) {
        return tokenCodec.issue(userId, "tester", TYPE_CUSTOMER).token();
    }

    /**
     * 构造一个用了不同密钥的配置，用于伪造令牌。
     *
     * @return 认证配置
     */
    private static AuthProperties forgedProperties() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("attacker-secret-0123456789abcdef");
        properties.setTokenExpireMinutes(60);
        return properties;
    }

    /**
     * 构造一条用户消息。
     *
     * @return 消息实体
     */
    private static Message userMessage() {
        return Message.builder()
                .id(1727138400000000001L)
                .sessionId(SESSION_ID)
                .role("user")
                .content("七天无理由退货怎么操作")
                .createTime(LocalDateTime.of(2024, 9, 24, 10, 0, 0))
                .status(1)
                .build();
    }

    /**
     * 构造一条带引用来源的助手消息。
     *
     * @return 消息实体
     */
    private static Message assistantMessage() {
        return Message.builder()
                .id(1727138400000000002L)
                .sessionId(SESSION_ID)
                .role("assistant")
                .content("签收后 7 天内可申请无理由退货。")
                .createTime(LocalDateTime.of(2024, 9, 24, 10, 0, 5))
                .agentName("RagSpecialist")
                .confidence(new BigDecimal("0.9200"))
                .refDocs(List.of(MessageRefDoc.builder()
                        .chunkId("1727138400000000003_2")
                        .docId(1727138400000000003L)
                        .docTitle("七天无理由退换货规则")
                        .chunkNo(2)
                        .score(0.95)
                        .used(1)
                        .build()))
                .status(1)
                .build();
    }
}
