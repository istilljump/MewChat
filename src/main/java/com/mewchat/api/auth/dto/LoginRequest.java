package com.mewchat.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <p>长度上限不是形式要求：请求体先于任何业务逻辑被反序列化，
 * 不设上限就等于允许调用方提交一个几 MB 的用户名，
 * 而这些字节会被完整读进内存并参与字符串比较。
 *
 * @param username 用户名
 * @param password 明文密码
 * @author MewChat
 */
public record LoginRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64 个字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password) {
}
