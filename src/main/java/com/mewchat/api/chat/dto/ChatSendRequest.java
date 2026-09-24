package com.mewchat.api.chat.dto;

import com.mewchat.common.constant.ChatConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起对话的请求体。
 *
 * <p><b>sessionId 由调用方传入而不是服务端生成</b>：同一轮对话的多条消息必须落到同一个会话里，
 * 服务端每次生成新 ID 就等于每句话都是一段新对话，多轮对话与记忆全部失效。
 * 新会话请先调 {@code POST /api/chat/session} 取一个 ID。
 *
 * <p>消息长度上限复用 {@link ChatConstants#MAX_MESSAGE_LENGTH}（与编排层同一常量）：
 * 若两处各写一个数字，接口放过、编排层拒绝，用户会收到一个说不清原因的错误。
 *
 * @param sessionId 会话业务ID
 * @param message   用户消息
 * @author MewChat
 */
public record ChatSendRequest(

        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 64, message = "sessionId 长度不能超过 64 个字符")
        String sessionId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = ChatConstants.MAX_MESSAGE_LENGTH,
                message = "消息长度不能超过 " + ChatConstants.MAX_MESSAGE_LENGTH + " 个字符")
        String message) {
}
