package com.mewchat.job;

import com.mewchat.agent.memory.ChatMemoryService;
import com.mewchat.config.JobProperties;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话收尾定时任务：关闭空闲会话并生成摘要。
 *
 * <p><b>为什么需要这个任务</b>：会话的"结束"没有显式信号 ——
 * 用户关掉页面就走了，不会有人来调用"结束会话"接口。若不做处理：
 * <ul>
 *     <li>会话永远停留在"进行中"，会话列表与统计都失真</li>
 *     <li>长时记忆（会话摘要）永远不会生成，超过短时记忆窗口的早期内容
 *         在后续对话中彻底丢失 —— Agent 会"忘记"用户半小时前说过什么</li>
 * </ul>
 * 因此以"多久没说话"作为判据，由定时任务统一收尾。
 *
 * <p><b>单批限量 + 逐条独立处理</b>：某一条会话生成摘要失败（模型限流、超时）
 * 不影响其它会话；失败的那条只记录日志，保持"进行中"状态，
 * 下一轮会重新尝试，天然具备重试能力。
 *
 * @author MewChat
 */
@Component
@ConditionalOnProperty(prefix = "mewchat.job.session-close", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SessionSummaryJob {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryJob.class);

    private final ConversationService conversationService;

    private final ChatMemoryService chatMemoryService;

    private final JobProperties jobProperties;

    public SessionSummaryJob(ConversationService conversationService,
                             ChatMemoryService chatMemoryService,
                             JobProperties jobProperties) {
        this.conversationService = conversationService;
        this.chatMemoryService = chatMemoryService;
        this.jobProperties = jobProperties;
    }

    /**
     * 扫描并关闭空闲会话。
     *
     * <p>用 fixedDelay 而不是 cron：相邻两次执行之间留出间隔，
     * 避免上一轮还没跑完就被再次触发。间隔与首次延迟都可在配置里调整。
     */
    @Scheduled(
            fixedDelayString = "${mewchat.job.session-close.interval-ms:600000}",
            initialDelayString = "${mewchat.job.session-close.initial-delay-ms:60000}")
    public void closeIdleSessions() {
        JobProperties.SessionClose config = jobProperties.getSessionClose();
        LocalDateTime idleBefore = LocalDateTime.now().minusMinutes(config.getIdleMinutes());

        List<Conversation> idleSessions =
                conversationService.listIdleActiveSessions(idleBefore, config.getBatchSize());
        if (idleSessions.isEmpty()) {
            log.debug("没有需要收尾的空闲会话");
            return;
        }

        log.info("开始收尾空闲会话，共 {} 个（空闲阈值 {} 分钟）",
                idleSessions.size(), config.getIdleMinutes());

        int closed = 0;
        for (Conversation conversation : idleSessions) {
            String sessionId = conversation.getSessionId();
            try {
                // closeSession 内部先生成摘要（长时记忆）再置为已结束
                chatMemoryService.closeSession(sessionId);
                closed++;
            } catch (Exception e) {
                // 单条失败不影响其它会话，保持"进行中"，下一轮自动重试
                log.error("会话 {} 收尾失败，将在下一轮重试", sessionId, e);
            }
        }
        log.info("空闲会话收尾完成：成功 {}/{}", closed, idleSessions.size());
    }
}
