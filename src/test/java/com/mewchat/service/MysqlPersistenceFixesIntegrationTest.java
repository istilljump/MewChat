package com.mewchat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
        // 1500 字：超过原先的列宽 1000，但不超过用户消息上限 2000。
        // 尾部拼一个唯一后缀：问题池按"归一化后的哈希"去重，用固定文案会让用例
        // 撞上库里已有的同一句话（那条可能已被标记为已优化），从而测到别的状态
        String question = uniqueQuestion("退", 1500);
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
     * 接单：待处理工单认领成功；已被同事接走或已结束的工单必须被拒。
     *
     * <p>"只有待处理能接"是写进 UPDATE 条件的（不是先查后写），因此并发接单时
     * 恰好一个成功 —— 这条用例在单线程下验证的是同一条件的判定面
     * （非待处理状态一律 0 行更新、如实报错）。
     */
    @Test
    void claimShouldTakePendingTicketAndRejectTakenOnes() {
        String sessionId = newSessionId();
        Long ticketId = ticketService.createFallbackTicket(
                sessionId, 1L, "需要人工的问题", BigDecimal.ZERO);
        User agentA = insertUser(AuthenticatedUser.TYPE_AGENT);
        User agentB = insertUser(AuthenticatedUser.TYPE_AGENT);

        Ticket claimed = ticketService.claim(ticketId, agentA.getId());
        assertThat(claimed.getHandlerId()).isEqualTo(agentA.getId());
        assertThat(claimed.getStatus()).as("接单后应进入处理中").isEqualTo(1);

        assertThatThrownBy(() -> ticketService.claim(ticketId, agentB.getId()))
                .as("已被同事接走的工单不能被抢")
                .hasMessageContaining("只有待处理");

        ticketService.resolve(ticketId);
        assertThatThrownBy(() -> ticketService.claim(ticketId, agentB.getId()))
                .as("已结束的工单同样不能接")
                .hasMessageContaining("只有待处理");
    }

    /**
     * "我的工单"必须真的按处理人过滤：我的单在我的列表里，别人的不在。
     *
     * <p>过滤失效（比如条件没拼进 SQL）不报任何错，只是把所有人的队列
     * 显示给每一个人 —— 这正是 {@code @MockitoBean} 的 Mapper 验证不了的。
     * 两个客服都是本轮新建的雪花ID，库里不可能有他们的存量工单，
     * 因此"agentB 的列表为空"就只能是过滤在生效。
     */
    @Test
    void myTicketsShouldOnlyContainOnesAssignedToMe() {
        String sessionId = newSessionId();
        Long ticketId = ticketService.createFallbackTicket(
                sessionId, 1L, "需要人工的问题", BigDecimal.ZERO);
        User agentA = insertUser(AuthenticatedUser.TYPE_AGENT);
        User agentB = insertUser(AuthenticatedUser.TYPE_AGENT);
        ticketService.claim(ticketId, agentA.getId());

        Page<Ticket> mine = ticketService.pageMyTickets(agentA.getId(), null, 1, 20);
        assertThat(mine.getRecords())
                .as("接过的单应出现在自己的列表里")
                .anySatisfy(t -> assertThat(t.getId()).isEqualTo(ticketId));

        Page<Ticket> theirs = ticketService.pageMyTickets(agentB.getId(), null, 1, 20);
        assertThat(theirs.getRecords())
                .as("别人的单绝不能出现在我的列表里")
                .noneSatisfy(t -> assertThat(t.getId()).isEqualTo(ticketId));
        assertThat(theirs.getRecords())
                .as("agentB 是本轮新建的账号，没有任何人给他派过单，列表应为空")
                .isEmpty();

        Page<Ticket> myOpen = ticketService.pageMyTickets(agentA.getId(), 1, 1, 20);
        assertThat(myOpen.getRecords())
                .as("带状态过滤时：接单后的工单在'处理中'列表里")
                .anySatisfy(t -> assertThat(t.getId()).isEqualTo(ticketId));
    }

    /**
     * 聚类结果要能写回问题池（阶段 9 遗留的"未在真库验证"项）。
     */
    @Test
    void clusterAssignmentShouldBeWrittenBack() {
        String sessionId = newSessionId();
        lowConfidenceQuestionService.record(uniqueQuestion("赠品什么时候发货"), BigDecimal.ZERO, sessionId);
        lowConfidenceQuestionService.record(uniqueQuestion("赠品何时寄出呢"), new BigDecimal("0.30"), sessionId);

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
        lowConfidenceQuestionService.record(uniqueQuestion("赠品什么时候发货"), BigDecimal.ZERO, sessionId);
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
        lowConfidenceQuestionService.record(uniqueQuestion("赠品什么时候发货"), BigDecimal.ZERO, sessionId);
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

    /* ==================== 会话列表与标题（对话页侧边栏） ==================== */

    /**
     * 首条用户消息兼作会话标题，且<b>只写一次</b>：后续消息不能把标题改掉。
     *
     * <p>"只写一次"是条件更新（UPDATE ... WHERE title 为空）保证的，
     * 若写成先查后写，用户连发两条消息时标题可能被第二条覆盖 ——
     * 会话列表里那段对话的标题就会随着最后一句话变来变去。
     */
    @Test
    void titleShouldBeSetOnceFromFirstUserMessage() {
        String sessionId = newSessionId();
        conversationService.getOrCreate(sessionId, 1L);

        String first = uniqueQuestion("邮费是多少");
        chatMemoryService.saveUserMessage(sessionId, first);
        assertThat(conversationService.getBySessionId(sessionId).getTitle())
                .as("标题应取首条用户消息")
                .isEqualTo(first);

        chatMemoryService.saveUserMessage(sessionId, "那满多少包邮呢");
        assertThat(conversationService.getBySessionId(sessionId).getTitle())
                .as("第二条消息不能改写标题")
                .isEqualTo(first);
    }

    /**
     * 标题超长时要被截断到列宽内（{@code title} 是 VARCHAR(100)）。
     *
     * <p>不截断的后果与真实缺陷同构：MySQL 严格模式下整条 UPDATE 被拒（1406），
     * 而这里失败只是"标题没写进去"—— 会话列表显示不出内容，却不报任何错。
     */
    @Test
    void overlongTitleShouldBeTruncated() {
        String sessionId = newSessionId();
        conversationService.getOrCreate(sessionId, 1L);

        chatMemoryService.saveUserMessage(sessionId, uniqueQuestion("退", 800));

        String title = conversationService.getBySessionId(sessionId).getTitle();
        assertThat(title)
                .as("超长标题必须被截断而不是写入失败")
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(100);
    }

    /**
     * "我的会话"列表要满足三件事：只看自己的、排除还没说过话的空会话、
     * 按最近活跃排序。
     *
     * <p>过滤与排序失效都不报错 —— 前者让侧边栏被空记录刷满，
     * 后者让用户在最上面看到一段很久以前的对话。这正是 Mock 验证不了的部分
     * （Mock 的 Mapper 不会真的按 SQL 过滤与排序）。
     */
    @Test
    void mySessionsShouldBeOwnedNonEmptyAndOrderedByActivity() {
        Long mine = 998_877_665L;
        Long other = 998_877_666L;

        String emptySession = newSessionId();
        conversationService.getOrCreate(emptySession, mine);

        String firstSession = newSessionId();
        conversationService.getOrCreate(firstSession, mine);
        chatMemoryService.saveUserMessage(firstSession, uniqueQuestion("第一段对话"));

        String secondSession = newSessionId();
        conversationService.getOrCreate(secondSession, mine);
        chatMemoryService.saveUserMessage(secondSession, uniqueQuestion("第二段对话"));

        String othersSession = newSessionId();
        conversationService.getOrCreate(othersSession, other);
        chatMemoryService.saveUserMessage(othersSession, uniqueQuestion("别人的对话"));

        List<String> ids = conversationService.listMine(mine, 50).stream()
                .map(Conversation::getSessionId)
                .toList();

        assertThat(ids)
                .as("只看得到自己的会话")
                .contains(firstSession, secondSession)
                .doesNotContain(othersSession);
        assertThat(ids)
                .as("还没说过话的空会话不进列表")
                .doesNotContain(emptySession);
        assertThat(ids.indexOf(secondSession))
                .as("最近活跃的排前面")
                .isLessThan(ids.indexOf(firstSession));
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
     * 给问题文案拼一个唯一后缀。
     *
     * <p><b>为什么必须唯一</b>：问题池是按"归一化后的哈希"去重的，
     * 用固定文案写测试就等于要求"库里从来没有过这句话" —— 这个前提在开发库上不成立
     * （联调时的演示数据、上一轮失败留下的数据都可能命中同一哈希），
     * 而命中的那一行可能处于任意状态（比如已被标记为已优化）。
     * 拼后缀之后，用例断言的就只是自己写进去的那一行，与库里原有内容无关。
     *
     * @param base 基础文案
     * @return 带唯一后缀的文案
     */
    private static String uniqueQuestion(String base) {
        return base + "（" + UUID.randomUUID() + "）";
    }

    /**
     * 构造一个"总长为 {@code totalLength} 且唯一"的问题文案。
     *
     * @param filler      填充字符
     * @param totalLength 期望总长度
     * @return 问题文案
     */
    private static String uniqueQuestion(String filler, int totalLength) {
        String suffix = UUID.randomUUID().toString();
        return filler.repeat(Math.max(1, totalLength - suffix.length())) + suffix;
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
