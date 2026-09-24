package com.mewchat.job;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.memory.ChatMemoryService;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.service.ConversationService;
import com.mewchat.support.StubChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话收尾定时任务的集成测试（需要 MySQL）。
 *
 * <p><b>为什么必须落在真库上</b>：这个任务是<b>长时记忆唯一的触发源</b> ——
 * 摘要只有它会被生成。而它做的事全是数据库状态流转（挑出空闲会话 →
 * 生成摘要 → 置为已结束 → 从"进行中"集合里消失），
 * 用 Mock 验证只能证明"我调用了自己定义的接口"。
 * 这里验的是三件真事：<b>空闲的会话被收尾且摘要落库</b>、
 * <b>刚刚还在聊的会话不能被动到</b>、<b>收尾后会话确实不再被扫到</b>。
 *
 * <p>大模型用确定性替身（{@link StubChatModel}）：这里验的是任务的调度与状态流转，
 * 不是摘要写得好不好；用替身也让"摘要写了什么"变成可断言的值。
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
        "mewchat.auth.token-secret=it-test-secret-0123456789abcdef",
        // 定时任务本身不要真的跑起来：本类直接调用方法，避免后台线程在断言中途改动数据
        "mewchat.job.session-close.initial-delay-ms=3600000",
        "mewchat.job.session-close.interval-ms=3600000"
})
@EnabledIfSystemProperty(named = "mewchat.it.mysql", matches = "true")
@Transactional
class SessionSummaryJobIntegrationTest {

    /** 空闲判定阈值，需与 yml 里的 idle-minutes 一致 */
    private static final int IDLE_MINUTES = 30;

    @Autowired
    private SessionSummaryJob job;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ChatMemoryService chatMemoryService;

    /**
     * 空闲会话应被收尾：生成摘要、置为已结束、写入结束时间。
     */
    @Test
    void idleSessionShouldBeClosedWithSummary() {
        String sessionId = newSessionId();
        seedConversation(sessionId, LocalDateTime.now().minusMinutes(IDLE_MINUTES + 10L));

        job.closeIdleSessions();

        Conversation closed = conversationService.getBySessionId(sessionId);
        assertThat(closed.getStatus())
                .as("空闲会话必须被置为已结束，否则长时记忆永远不会产生")
                .isEqualTo(2);
        assertThat(closed.getEndTime()).as("结束时间要落库，供后台与统计使用").isNotNull();
        assertThat(closed.getSummary())
                .as("摘要必须真的写进会话表，这是长时记忆的载体")
                .isEqualTo(StubChatModel.SUMMARY_TEXT);
    }

    /**
     * 刚刚还在对话的会话不能被误收尾。
     *
     * <p>这是本任务最要紧的一条：判定条件写错（比如把"大于"写成"小于"、
     * 或者漏掉 `last_message_time` 为空的分支）时，正在聊天的用户会被直接判为已结束，
     * 上下文随之丢掉。用例拿一个"刚刚有新消息"的会话做对照。
     */
    @Test
    void activeSessionShouldNotBeTouched() {
        String sessionId = newSessionId();
        seedConversation(sessionId, LocalDateTime.now());

        job.closeIdleSessions();

        Conversation active = conversationService.getBySessionId(sessionId);
        assertThat(active.getStatus()).as("正在对话的会话不能被动到").isEqualTo(1);
        assertThat(active.getEndTime()).isNull();
        assertThat(active.getSummary()).as("未收尾就不该生成摘要").isNull();
    }

    /**
     * 收尾之后再发消息，会话要重新变回进行中，并能被再次收尾。
     *
     * <p>把两个机制接起来看：任务只扫描 {@code status=1} 的会话，
     * 因此"收尾 → 用户继续聊 → 再次收尾"这条链必须能闭合，
     * 否则那个会话的摘要会永远停在第一次收尾的时刻。
     */
    @Test
    void reopenedSessionShouldBePickableAgain() {
        String sessionId = newSessionId();
        seedConversation(sessionId, LocalDateTime.now().minusMinutes(IDLE_MINUTES + 10L));
        job.closeIdleSessions();
        assertThat(conversationService.getBySessionId(sessionId).getStatus()).isEqualTo(2);
        int countBeforeReopen = conversationService.getBySessionId(sessionId).getMessageCount();

        // 用户又发了一条消息，并把最后活动时间推回到"很久没动"
        conversationService.touchOnNewMessage(sessionId, LocalDateTime.now());
        assertThat(conversationService.getBySessionId(sessionId).getStatus())
                .as("收到新消息后应重新激活")
                .isEqualTo(1);
        conversationService.touchOnNewMessage(sessionId, LocalDateTime.now().minusMinutes(IDLE_MINUTES + 10L));

        job.closeIdleSessions();

        Conversation closedAgain = conversationService.getBySessionId(sessionId);
        assertThat(closedAgain.getStatus())
                .as("重新激活的会话必须能被再次收尾，否则它的摘要再也不会更新")
                .isEqualTo(2);
        assertThat(closedAgain.getMessageCount())
                .as("重新激活后收到的消息同样要计入")
                .isEqualTo(countBeforeReopen + 2);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 造一个会话：一条用户消息 + 一条助手回复，供摘要使用。
     *
     * @param sessionId      会话ID
     * @param lastActiveTime 最后活动时间（决定它是否"空闲"）
     */
    private void seedConversation(String sessionId, LocalDateTime lastActiveTime) {
        conversationService.getOrCreate(sessionId, 1L);
        chatMemoryService.saveUserMessage(sessionId, "七天无理由退货怎么操作");
        chatMemoryService.saveAssistantReply(ChatContext.builder()
                .sessionId(sessionId)
                .userMessage("七天无理由退货怎么操作")
                .replyText("签收之日起 7 天内可申请无理由退货。")
                .finalState(ChatState.REPLY)
                .build());
        // 用 touchOnNewMessage 把最后活动时间推到指定时刻：它本身就是"有新消息"的入口，
        // 不必再写一条 update 绕过业务方法
        conversationService.touchOnNewMessage(sessionId, lastActiveTime);
    }

    /**
     * 构造一个不重复的会话ID。
     *
     * @return 会话业务ID
     */
    private static String newSessionId() {
        return "it-job-" + UUID.randomUUID();
    }

    /**
     * 测试配置：用确定性替身替换摘要所用的大模型。
     */
    @TestConfiguration
    static class StubConfig {

        /**
         * 对话模型替身。
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
