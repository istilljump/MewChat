package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.common.constant.ChatConstants;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.mapper.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 消息业务服务实现。
 *
 * @author MewChat
 */
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageServiceImpl.class);

    /**
     * 消息不可用时的统一提示。
     *
     * <p>与会话的 {@code SESSION_UNAVAILABLE_MESSAGE} 同一口径：不区分
     * "不存在"与"不属于你"，避免被人用来枚举他人的消息ID。
     */
    static final String MESSAGE_UNAVAILABLE_MESSAGE = "消息不存在或无权访问";

    /**
     * 会话服务：反馈需要沿"消息 → 会话 → 用户"校验归属。
     *
     * <p>依赖方向是 service → service（{@code ConversationServiceImpl} 不依赖本类，
     * 因此不存在循环依赖）；自己注入 {@code ConversationMapper} 去查归属，
     * 等于绕过 service 直接摸 dao，分层就不作数了。
     */
    private final ConversationService conversationService;

    public MessageServiceImpl(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public List<Message> listRecentBySessionId(String sessionId, int limit) {
        if (!StringUtils.hasText(sessionId) || limit <= 0) {
            return List.of();
        }

        // 取"最近 N 条"要按 id 倒序，再把结果翻回正序，这样调用方拿到的始终是时间升序。
        // 用分页插件而不是手写 LIMIT 字符串：不必拼 SQL。
        //
        // 但 maxLimit 要显式给：分页插件默认套用全局单页上限(100)，请求条数大于它时
        // 会被静默改小。这里传进来的条数由 mewchat.memory.max-history-rounds 决定，
        // 若把轮数配到 100 以上，短时记忆就会被无声截断 —— 表现为"Agent 忽然忘了最近几轮"，
        // 而日志里没有任何迹象。保护"单页不许太大"仍由接口层的 clampPageSize 负责。
        Page<Message> page = Page.of(1, limit, false);
        page.setMaxLimit((long) limit);
        Page<Message> result = page(page, Wrappers.<Message>lambdaQuery()
                .eq(Message::getSessionId, sessionId)
                .orderByDesc(Message::getId));

        List<Message> records = new ArrayList<>(result.getRecords());
        Collections.reverse(records);
        return records;
    }

    @Override
    public List<Message> listAllBySessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(Message::getSessionId, sessionId)
                .orderByAsc(Message::getId)
                .list();
    }

    @Override
    public Message recordFeedback(Long messageId, Long userId, int vote) {
        if (vote != FEEDBACK_UP && vote != FEEDBACK_DOWN) {
            // 取值校验放在服务层而不是只靠接口层的 DTO：将来若多一个入口
            // （比如工单里补反馈），这条规则不会因为漏改而失效
            throw new BizException(ResultCode.PARAM_INVALID, "反馈值只能是 1（有用）或 2（无用）");
        }
        Message message = requireOwnedAssistantMessage(messageId, userId);

        lambdaUpdate()
                .eq(Message::getId, message.getId())
                .set(Message::getFeedback, vote)
                .set(Message::getFeedbackTime, LocalDateTime.now())
                .update();

        log.info("收到用户反馈：messageId={} userId={} vote={}", messageId, userId, vote);
        return getById(messageId);
    }

    /**
     * 取出消息并校验"它属于该用户、且是助手回答"。
     *
     * <p>沿"消息 → 会话 → 用户"这条链校验归属，与读取会话历史同一口径：
     * 不通过时抛出<b>同样的提示</b>，不区分"消息不存在"与"不是你的消息" ——
     * 能区分就等于告诉调用方这个ID确实存在，可被用来枚举他人的消息。
     *
     * <p>非助手消息单独报错（而不是也报"无权访问"）：这是调用方用错了对象，
     * 不是权限问题，报"只能对回答反馈"才让人知道该怎么改。
     *
     * @param messageId 消息ID
     * @param userId    当前用户ID
     * @return 消息实体
     * @throws BizException 消息不存在、不属于该用户，或不是助手消息时抛出
     */
    private Message requireOwnedAssistantMessage(Long messageId, Long userId) {
        if (messageId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "消息ID不能为空");
        }
        Message message = getById(messageId);
        if (message == null) {
            throw new BizException(ResultCode.FORBIDDEN, MESSAGE_UNAVAILABLE_MESSAGE);
        }
        // 归属校验用会话服务：消息表里没有 userId，归属只存在于会话上，
        // 在这里自己查会话表就等于绕过 service 直接摸 dao
        if (conversationService.getOwnedBySessionId(message.getSessionId(), userId) == null) {
            throw new BizException(ResultCode.FORBIDDEN, MESSAGE_UNAVAILABLE_MESSAGE);
        }
        if (!ChatConstants.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BizException(ResultCode.PARAM_INVALID, "只能对助手的回答做反馈");
        }
        return message;
    }
}
