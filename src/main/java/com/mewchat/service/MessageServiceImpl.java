package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
}
