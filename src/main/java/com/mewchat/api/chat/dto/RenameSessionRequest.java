package com.mewchat.api.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话重命名请求体。
 *
 * <p>长度上限对齐 {@code conversation.title} 的列宽（VARCHAR(100)，按字符计）。
 * 刻意<b>不在服务端静默截断</b>：重命名是用户的显式输入，悄悄截断等于改用户的话 ——
 * 超限时明确报参数错误，让用户自己删减（首句自动标题的 50 字截断是另一条路径，
 * 那里截断的是"程序自己起的标题"，两处规则各自成立）。
 *
 * @param title 新标题
 * @author MewChat
 */
public record RenameSessionRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "标题长度不能超过 100 个字符")
        String title) {
}
