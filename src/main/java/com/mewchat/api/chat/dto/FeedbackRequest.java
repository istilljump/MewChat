package com.mewchat.api.chat.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 反馈请求体。
 *
 * <p>{@code vote} 只能是 {@code up} / {@code down}（见 {@link FeedbackVote}），
 * 解析失败会被全局处理器转成 10001，不需要控制器自己校验取值。
 *
 * @param vote 反馈取值
 * @author MewChat
 */
public record FeedbackRequest(@NotNull(message = "vote 不能为空") FeedbackVote vote) {
}
