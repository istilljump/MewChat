package com.mewchat.service;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.memory.ChatMemoryService;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.PendingClarification;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.dao.mysql.entity.User;
import com.mewchat.dao.mysql.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真库持久化行为的集成测试（需要 MySQL）。
 *
 * <p><b>为什么要单独一个类</b>：此前有一批行为只有"编译期与 Mock 保证"——
 * JSON 列的类型处理、超长文本被列宽拒绝、并发下的建单幂等、指派工单的用户校验。
 * 它们的共同点是<b>失败时都不报错</b>：JSON 解析错了读回来是一串文本、
 * 超长被数据库拒绝只是少了一条记录、幂等失效只是多出一张单。
 * 用 Mock 验证这类行为等于自证 —— Mock 的 Mapper 不会因为列宽而拒绝写入，
 * 也不会因为 typeHandler 没生效而返回未解析的字符串。因此这里全部落在真库上。
 *
 * <p>每个用例都包在事务里并回滚，跑完不在库里留数据（与
 * {@code ChatSupervisorIntegrationTest} 同一策略）。
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
        "mewchat.auth.token-secret=it-test-secret-0123456789abcdef"
})
@EnabledIfSystemProperty(named = "mewchat.it.mysql", matches = "true")
@Transactional
class MysqlPersistenceFixesIntegrationTest {

    /** 超过 message.error_msg 列宽（500）的错误文本 */
    private static final String LONG_ERROR = "x".repeat(1200);

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private LowConfidenceQuestionService lowConfidenceQuestionService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private UserMapper userMapper;

    /* ==================== 超长文本不能把整条记录带走 ==================== */

    /**
     * 失败原因超过列宽时截断入库，助手回复本身必须留下来。
     *
     * <p>不截断的后果不是"错误详情丢了"，而是整条 INSERT 被 MySQL 拒绝（1406），
     * 用户在历史里根本看不到这轮回答，日志里只有一句"落库失败"。
     */
    @Test
    void overlongErrorMessageShouldBeTruncatedWithoutLosingTheReply() {
        String sessionId = newSessionId();
        conversationService.getOrCreate(sessionId, 1L);

        chatMemoryService.saveAssistantReply(ChatContext.builder()
                .sessionId(sessionId)
                .userMessage("出问题的问题")
                .replyText("兜底回复")
                .finalState(ChatState.FALLBACK)
                .errorMessage(LONG_ERROR)
                .build());

        List<Message> messages = messageService.listAllBySessionId(sessionId);
        assertThat(messages)
                .as("助手回复必须真的落库，不能因为错误信息过长而整行丢失")
                .hasSize(1);
        Message assistant = messages.get(0);
        assertThat(assistant.getContent()).isEqualTo("兜底回复");
        assertThat(assistant.getStatus()).as("有失败原因时应标记为失败").isZero();
        assertThat(assistant.getErrorMsg())
                .as("错误信息应被截断到列宽以内")
                .hasSize(500)
                .isEqualTo(LONG_ERROR.substring(0, 500));
    }

    /**
     * 字数接近用户消息上限的兜底问题必须能进池，且原文被截断到列宽以内。
     *
     * <p>问题池列窄于消息上限时，最长的那批问题（往往也是最该被优化的）会写入失败，
     * 而写入方的异常只记日志 —— 数据飞轮静默少一块。
     */
    @Test
    void overlongQuestionShouldStillEnterThePool() {
        // 1500 字：超过原先的列宽 1000，但不超过用户消息上限 2000
        String question = "退".repeat(1500);
        String sessionId = newSessionId();

        lowConfidenceQuestionService.record(question, new BigDecimal("0.10"), sessionId);

        LowConfidenceQuestion recorded = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getSessionId, sessionId)
                .one();
        assertThat(recorded)
                .as("超长问题必须能进池，否则数据飞轮会漏掉最复杂的那批问题")
                .isNotNull();
        assertThat(recorded.getQuestion()).hasSize(1500);
    }

    /* ==================== 挂起澄清状态的往返 ==================== */

    /**
     * 挂起状态（JSON 列）写入后必须能原样读回，清空后必须真的变成 null。
     *
     * <p>这一条有两个已知的坑，任何一个踩中都不报错：
     * ①实体没带 {@code autoResultMap} 时读回来是<b>未解析的 JSON 文本</b>，表现为
     * "字段不为空但取不到任何内容"；②清空时若用默认的
     * {@code FieldStrategy}，值为 null 的列不会进 UPDATE 语句 ——
     * 表现为"用户早就答完了，之后随口回一个 1 又被续接回旧追问"。
     */
    @Test
    void pendingClarificationShouldRoundTripAndClear() {
        String sessionId = newSessionId();
        conversationService.getOrCreate(sessionId, 1L);

        PendingClarification pending = new PendingClarification();
        pending.setQuestion("帮我查订单");
        pending.setIntent("ORDER_QUERY");
        pending.setMissingParam("orderNo");
        pending.setCreatedAt(LocalDateTime.now());
        PendingClarification.Option option = new PendingClarification.Option();
        option.setValue("MC202409240001");
        option.setLabel("MC202409240001 无线蓝牙耳机 Pro（已发货）");
        pending.setOptions(List.of(option));

        conversationService.savePendingClarification(sessionId, pending);

        PendingClarification loaded = conversationService.getPendingClarification(sessionId);
        assertThat(loaded)
                .as("JSON 列必须解析回对象，拿到的不能是一串未解析的文本")
                .isNotNull();
        assertThat(loaded.getIntent()).isEqualTo("ORDER_QUERY");
        assertThat(loaded.getMissingParam()).isEqualTo("orderNo");
        assertThat(loaded.getOptions()).hasSize(1);
        assertThat(loaded.getOptions().get(0).getValue()).isEqualTo("MC202409240001");
        assertThat(loaded.getOptions().get(0).getLabel()).contains("无线蓝牙耳机 Pro");

        conversationService.clearPendingClarification(sessionId);

        assertThat(conversationService.getPendingClarification(sessionId))
                .as("清空必须真的把列置为 null，否则用户之后随口回一个数字会被续接回旧追问")
                .isNull();
    }

    /* ==================== 会话收尾之后还能继续对话 ==================== */

    /**
     * 已被收尾的会话收到新消息时，必须重新变回"进行中"。
     *
     * <p>不收尾就继续对话本身不报错，但会有两个后果：后台一直显示"已结束"，
     * 以及收尾任务只扫描进行中的会话 —— 这个会话的长时记忆<b>再也不会刷新</b>。
     */
    @Test
    void newMessageShouldReactivateClosedConversation() {
        String sessionId = newSessionId();
        conversationService.getOrCreate(sessionId, 1L);
        conversationService.closeSession(sessionId);

        Conversation closed = conversationService.getBySessionId(sessionId);
        assertThat(closed.getStatus()).as("前置条件：会话已收尾").isEqualTo(2);
        assertThat(closed.getEndTime()).isNotNull();

        conversationService.touchOnNewMessage(sessionId, LocalDateTime.now());

        Conversation reopened = conversationService.getBySessionId(sessionId);
        assertThat(reopened.getStatus())
                .as("收到新消息的会话必须回到进行中，否则它的摘要永远不会再更新")
                .isEqualTo(1);
        assertThat(reopened.getEndTime()).as("重新激活后不应再留着结束时间").isNull();
        assertThat(reopened.getMessageCount()).isEqualTo(1);
    }

    /* ==================== 工单 ==================== */

    /**
     * 同一会话重复兜底只留一张未关闭工单。
     */
    @Test
    void repeatedFallbackShouldReuseTheOpenTicket() {
        String sessionId = newSessionId();

        Long first = ticketService.createFallbackTicket(
                sessionId, 1L, "答不上来的问题", BigDecimal.ZERO);
        Long second = ticketService.createFallbackTicket(
                sessionId, 1L, "同一个问题的另一种问法", new BigDecimal("0.20"));

        assertThat(first).as("首次兜底应建单").isNotNull();
        assertThat(second)
                .as("同一会话已有未关闭工单时应复用它，否则客服工作台会被同一会话刷屏")
                .isEqualTo(first);
    }

    /**
     * 指派处理人必须校验对方确实是客服或管理员。
     *
     * <p>不校验的后果：一个打错的ID（甚至某个客户的ID）也会让工单变成"处理中" ——
     * 它从待处理队列里消失、看起来有人管了，实际没有任何客服接手。
     */
    @Test
    void assignShouldRejectNonAgentHandler() {
        String sessionId = newSessionId();
        Long ticketId = ticketService.createFallbackTicket(
                sessionId, 1L, "需要人工的问题", BigDecimal.ZERO);

        User customer = insertUser(AuthenticatedUser.TYPE_CUSTOMER);
        User agent = insertUser(AuthenticatedUser.TYPE_AGENT);

        assertThatThrownBy(() -> ticketService.assign(ticketId, customer.getId()))
                .as("客户不能被指派为工单处理人")
                .hasMessageContaining("不是客服或管理员");

        assertThatThrownBy(() -> ticketService.assign(ticketId, 999_999_999_999L))
                .as("不存在的用户不能被指派")
                .hasMessageContaining("不存在");

        Ticket assigned = ticketService.assign(ticketId, agent.getId());
        assertThat(assigned.getHandlerId()).isEqualTo(agent.getId());
        assertThat(assigned.getStatus()).isEqualTo(1);
    }

    /**
     * 聚类结果要能写回问题池（阶段 9 遗留的"未在真库验证"项）。
     */
    @Test
    void clusterAssignmentShouldBeWrittenBack() {
        String sessionId = newSessionId();
        lowConfidenceQuestionService.record("赠品什么时候发货", BigDecimal.ZERO, sessionId);
        lowConfidenceQuestionService.record("赠品何时寄出呢", new BigDecimal("0.30"), sessionId);

        List<LowConfidenceQuestion> rows = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getSessionId, sessionId)
                .list();
        assertThat(rows).hasSize(2);
        List<Long> ids = rows.stream().map(LowConfidenceQuestion::getId).toList();

        int updated = lowConfidenceQuestionService.assignCluster(ids, "cluster-key-1", ids.size());

        assertThat(updated).isEqualTo(2);
        assertThat(lowConfidenceQuestionService.lambdaQuery()
                .in(LowConfidenceQuestion::getId, ids)
                .list())
                .allSatisfy(row -> {
                    assertThat(row.getClusterKey()).isEqualTo("cluster-key-1");
                    assertThat(row.getClusterSize()).isEqualTo(2);
                });
    }

    /**
     * 飞轮的收口：标记已优化后，该问题要真的从待优化清单里消失。
     *
     * <p>这套机制的价值全在"清单会收敛"上：如果状态写不回去，
     * 运营每轮都会看到同一批问题、重复处理，数据飞轮就只是个报表。
     * 因此这里断言的不只是两个字段，还有<b>它不再出现在清单查询的结果里</b>。
     */
    @Test
    void markingOptimizedShouldRemoveQuestionFromChecklist() {
        String sessionId = newSessionId();
        lowConfidenceQuestionService.record("赠品什么时候发货", BigDecimal.ZERO, sessionId);
        LowConfidenceQuestion question = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getSessionId, sessionId)
                .one();
        assertThat(question).isNotNull();

        boolean updated = lowConfidenceQuestionService.markOptimized(question.getId(), 999001L);

        assertThat(updated).as("首次标记应当更新成功").isTrue();
        LowConfidenceQuestion reloaded = lowConfidenceQuestionService.getById(question.getId());
        assertThat(reloaded.getOptimized()).as("状态要写成已优化").isEqualTo(1);
        assertThat(reloaded.getKnowledgeDocId()).as("必须记住是哪篇文档解决的").isEqualTo(999001L);
        assertThat(reloaded.getOptimizeTime()).as("优化时间要落库，供后续统计").isNotNull();

        assertThat(lowConfidenceQuestionService.listPendingForChecklist(200))
                .as("已优化的问题必须从待优化清单里消失，否则清单永远不会收敛")
                .noneSatisfy(row -> assertThat(row.getId()).isEqualTo(question.getId()));
    }

    /**
     * 重复标记同一个问题不应生效：否则"再点一次"会把优化时间刷新掉，
     * 让"什么时候解决的"这个信息失真。
     */
    @Test
    void markingTwiceShouldNotUpdateAgain() {
        String sessionId = newSessionId();
        lowConfidenceQuestionService.record("赠品什么时候发货", BigDecimal.ZERO, sessionId);
        Long id = lowConfidenceQuestionService.lambdaQuery()
                .eq(LowConfidenceQuestion::getSessionId, sessionId)
                .one()
                .getId();

        assertThat(lowConfidenceQuestionService.markOptimized(id, 999001L)).isTrue();
        assertThat(lowConfidenceQuestionService.markOptimized(id, 999002L))
                .as("已优化的问题不该被再次改动")
                .isFalse();
        assertThat(lowConfidenceQuestionService.getById(id).getKnowledgeDocId())
                .as("关联的文档不能被后一次调用改写")
                .isEqualTo(999001L);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 插入一个测试用户。
     *
     * @param userType 用户类型（1客户 2客服 3管理员）
     * @return 用户（回填了雪花ID）
     */
    private User insertUser(int userType) {
        User user = User.builder()
                .username("it-" + UUID.randomUUID().toString().substring(0, 8))
                .password("x")
                .nickname("测试用户")
                .userType(userType)
                .status(1)
                .build();
        userMapper.insert(user);
        return user;
    }

    /**
     * 构造一个不重复的会话ID。
     *
     * @return 会话业务ID
     */
    private static String newSessionId() {
        return "it-fix-" + UUID.randomUUID();
    }
}
