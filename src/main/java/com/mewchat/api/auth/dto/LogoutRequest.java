package com.mewchat.api.auth.dto;

/**
 * 登出请求。
 *
 * <p>刷新令牌是登出的凭证：持有它才证明"是登录的那个人在登出"，
 * 接口因此可以免认证（与 /refresh 同一把钥匙）。字段允许为空 ——
 * 前端丢失了刷新令牌也应能完成本地登出，服务端幂等地返回成功。
 *
 * @param refreshToken 要吊销的刷新令牌
 * @author MewChat
 */
public record LogoutRequest(String refreshToken) {
}
