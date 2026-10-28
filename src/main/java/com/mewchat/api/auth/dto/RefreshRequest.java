package com.mewchat.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新请求。
 *
 * <p>刷新令牌本身就是这一请求的凭证 —— 不需要也不应该再带访问令牌
 * （访问令牌过期了才需要刷新，要求它有效是本末倒置）。
 *
 * @param refreshToken 登录/上一次刷新下发的刷新令牌
 * @author MewChat
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
