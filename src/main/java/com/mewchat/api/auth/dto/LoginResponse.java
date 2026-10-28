package com.mewchat.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 登录/刷新响应。
 *
 * <p>只返回令牌与展示信息，<b>不返回密码哈希等任何凭据字段</b>。
 *
 * <p>令牌对（访问 + 刷新）同时下发：访问令牌管"每个请求的身份证明"（无状态），
 * 刷新令牌管"静默续期与登出"（可吊销）。前端应把两者都存起来，
 * 在访问令牌到期前用刷新令牌换新对。
 *
 * @param token             访问令牌，后续请求放在 {@code Authorization: Bearer <token>}
 * @param expiresAt         访问令牌过期时间，前端据此安排静默续期
 * @param refreshToken      刷新令牌，只发往 /api/auth/refresh 与 /api/auth/logout
 * @param refreshExpiresAt  刷新令牌过期时间；超过它只能重新登录
 * @param userId            用户ID
 * @param username          用户名
 * @param nickname          昵称，可能为空
 * @author MewChat
 */
public record LoginResponse(

        String token,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        Instant expiresAt,

        String refreshToken,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime refreshExpiresAt,

        Long userId,

        String username,

        String nickname) {
}
