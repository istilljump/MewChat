package com.mewchat.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 登录响应。
 *
 * <p>只返回令牌与展示信息，<b>不返回密码哈希等任何凭据字段</b>。
 *
 * @param token     访问令牌，后续请求放在 {@code Authorization: Bearer <token>}
 * @param expiresAt 令牌过期时间。前端据此在过期前提示重新登录
 * @param userId    用户ID
 * @param username  用户名
 * @param nickname  昵称，可能为空
 * @author MewChat
 */
public record LoginResponse(

        String token,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        Instant expiresAt,

        Long userId,

        String username,

        String nickname) {
}
