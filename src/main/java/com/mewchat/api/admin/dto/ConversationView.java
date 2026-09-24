package com.mewchat.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 会话视图（后台）。
 *
 * @param id                     会话主键
 * @param sessionId              会话业务ID
 * @param userId                 所属用户ID；为空表示未登录的游客会话
 * @param title                  会话标题
 * @param status                 状态：1进行中 2已结束 3已转人工
 * @param statusLabel            状态中文说明
 * @param messageCount           消息条数
 * @param summary                AI 生成的会话摘要，供客服快速了解上下文
 * @param hasPendingClarification 是否有一个还没被回答的追问（用户卡在半路）
 * @param startTime              会话开始时间
 * @param endTime                会话结束时间，未结束为空
 * @param lastMessageTime        最后一条消息时间
 * @author MewChat
 */
public record ConversationView(

        Long id,

        String sessionId,

        Long userId,

        String title,

        Integer status,

        String statusLabel,

        Integer messageCount,

        String summary,

        boolean hasPendingClarification,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime startTime,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime endTime,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime lastMessageTime) {

    /** 会话状态：进行中 */
    public static final int STATUS_ACTIVE = 1;

    /** 会话状态：已结束 */
    public static final int STATUS_CLOSED = 2;

    /** 会话状态：已转人工 */
    public static final int STATUS_HANDOFF = 3;

    /**
     * 把会话状态转成中文说明。
     *
     * @param status 状态值
     * @return 中文说明
     */
    public static String statusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_ACTIVE -> "进行中";
            case STATUS_CLOSED -> "已结束";
            case STATUS_HANDOFF -> "已转人工";
            default -> "未知(" + status + ")";
        };
    }
}
