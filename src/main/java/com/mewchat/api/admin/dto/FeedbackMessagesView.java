package com.mewchat.api.admin.dto;

import java.util.List;

/**
 * 反馈消息视图（运营后台）：按点赞/点踩筛选的助手消息列表。
 *
 * <p>这是"用户反馈进飞轮"的入口 —— 点踩密集的回答就是最真实的待优化清单。
 *
 * @param total   满足条件的消息总数
 * @param records 当前页记录
 * @author MewChat
 */
public record FeedbackMessagesView(
        long total,
        List<Item> records) {

    /**
     * 单条反馈消息。
     *
     * @param messageId    助手消息ID（雪花ID，接口层序列化为字符串）
     * @param sessionId    所属会话ID（用于跳转查看完整对话）
     * @param question     触发该回答的用户提问原文（取不到时为空串）
     * @param answer       助手回答摘要（服务端截断到 200 字）
     * @param feedback     反馈值：1 有用 / 2 无用
     * @param feedbackTime 反馈时间， ISO-8601 字符串
     */
    public record Item(
            String messageId,
            String sessionId,
            String question,
            String answer,
            Integer feedback,
            String feedbackTime) {
    }
}
