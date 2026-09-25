package com.mewchat.service;

import com.mewchat.common.exception.BizException;
import com.mewchat.dao.mysql.entity.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话清理服务：删除会话时连同它的消息一起处理。
 *
 * <p><b>为什么单独一个类，而不是把方法加进 ConversationService</b>：
 * 删除要同时动会话与消息两张表，是<b>跨聚合</b>的操作 ——
 * 放进 {@code ConversationService} 就得让它依赖 {@code MessageService}，
 * 而 {@code MessageService} 已经依赖 {@code ConversationService}（反馈要校验会话归属），
 * 两个 service 互相注入会形成循环依赖，Spring 启动直接失败。
 * 与其为了绕开它去直接注入对方的 Mapper（那是把分层当摆设），
 * 不如让这个"协作型"用例单独成类：它只依赖两个服务、不被任何服务依赖，天然无环。
 *
 * <p><b>事务</b>：消息与会话的删除必须同生共死。中途失败若只删了一半，
 * 会出现"会话还在、历史空了"或"会话没了、消息留在统计里"两种都很难解释的状态。
 *
 * <p><b>删除语义（如实的取舍）</b>：会话走逻辑删除（全库统一，且会话ID不会被复用），
 * 消息走物理删除（{@code message} 表没有逻辑删除列）。结果是<b>用户删了就是真删</b> ——
 * 客服后台也查不到这段对话了。真实客服系统通常还要保留会话用于审计与合规，
 * 那需要一个保留策略（例如只逻辑删除、后台仍可见），本项目按"用户可清理自己的数据"这一直觉实现。
 *
 * @author MewChat
 */
@Service
public class ConversationCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCleanupService.class);

    private final ConversationService conversationService;

    private final MessageService messageService;

    public ConversationCleanupService(ConversationService conversationService,
                                      MessageService messageService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    /**
     * 删除一个会话及其全部消息。
     *
     * <p>归属校验在会话服务里（不通过时返回 null），这里用与会话历史<b>同一个提示</b> ——
     * 不区分"不存在"与"不是你的"，否则可被用来枚举他人的会话。
     *
     * @param sessionId 会话业务ID
     * @param userId    当前用户ID
     * @return 删除的消息条数（供日志与前端提示用）
     * @throws BizException 会话不存在或不属于该用户时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteMine(String sessionId, Long userId) {
        Conversation conversation = conversationService.getOwnedBySessionId(sessionId, userId);
        if (conversation == null) {
            throw new BizException(com.mewchat.common.result.ResultCode.FORBIDDEN,
                    ConversationService.SESSION_UNAVAILABLE_MESSAGE);
        }

        int messages = messageService.deleteBySessionIds(List.of(sessionId));
        conversationService.removeById(conversation.getId());
        log.info("会话已删除：session={} userId={} 消息={} 条", sessionId, userId, messages);
        return messages;
    }

    /**
     * 清空当前用户的全部会话及其消息。
     *
     * <p>先查出会话ID再按 ID 批量删消息：不按 userId 关联删消息，是因为
     * {@code message} 表里没有 userId（归属只存在于会话上），
     * 沿会话ID删除才能保证"只删自己的"。
     *
     * <p>条数上限沿用会话列表的口径（一次最多 100 个会话）：
     * 超出部分不会被删掉，前端下次进入仍会看到 —— 比"悄悄只删一部分"更诚实。
     *
     * @param userId 当前用户ID
     * @return 本次删除的会话数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteAllMine(Long userId) {
        List<Conversation> mine = conversationService.listMine(userId, 0);
        if (mine.isEmpty()) {
            return 0;
        }
        List<String> sessionIds = mine.stream().map(Conversation::getSessionId).toList();
        int messages = messageService.deleteBySessionIds(sessionIds);
        mine.forEach(conversation -> conversationService.removeById(conversation.getId()));
        log.info("用户清空了自己的会话：userId={} 会话={} 个 消息={} 条",
                userId, mine.size(), messages);
        return mine.size();
    }
}
