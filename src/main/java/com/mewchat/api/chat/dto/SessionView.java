package com.mewchat.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 会话列表里的一条会话（对话页侧边栏"会话记录"）。
 *
 * <p>只暴露侧边栏要渲染的四个字段：会话ID（点击时用它拉历史）、标题、消息数、
 * 最近活跃时间。<b>不透出</b>用户ID、摘要、挂起状态等字段 —— 那些要么是内部状态，
 * 要么（摘要）本就属于服务端记忆，没必要出现在列表接口里。
 *
 * @param sessionId       会话业务ID
 * @param title           会话标题，取首条用户消息；从未说过话时为空串
 * @param messageCount    消息条数（用户+助手），用于列表里显示条数
 * @param lastMessageTime 最近一条消息的时间；从未说过话时为 null
 * @author MewChat
 */
public record SessionView(

        String sessionId,

        String title,

        Integer messageCount,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime lastMessageTime) {
}
