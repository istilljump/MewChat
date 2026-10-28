package com.mewchat.service;

import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.common.security.TokenCodec;
import com.mewchat.config.AuthProperties;
import com.mewchat.dao.mysql.entity.User;
import com.mewchat.dao.mysql.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新令牌链路中 {@link AuthService} 决策部分的单元测试（不依赖数据库）。
 *
 * <p>刷新令牌本身的存储、旋转与吊销语义由需要 MySQL 的集成测试验证
 * （条件更新、逻辑删除这类行为 Mock 验证不了 —— Mock 的 Mapper 不会做
 * 逻辑删除过滤，见阶段 11 的教训）；这里钉的是服务层的三个决策：
 * <b>禁用账号必须终止续期</b>（否则禁用对还揣着刷新令牌的人不起作用）、
 * <b>用户已不存在时按登录过期处理</b>、以及参数校验。
 *
 * @author MewChat
 */
class AuthServiceRefreshTest {

    private UserMapper userMapper;

    private TokenCodec tokenCodec;

    private RefreshTokenService refreshTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        tokenCodec = mock(TokenCodec.class);
        refreshTokenService = mock(RefreshTokenService.class);
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("unit-test-secret-0123456789abcdef");
        authService = new AuthService(userMapper, tokenCodec,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(),
                refreshTokenService, properties);
    }

    /**
     * 正常刷新：旋转出归属 → 签发新令牌对。
     */
    @Test
    void refreshShouldIssueNewTokenPair() {
        when(refreshTokenService.rotate(eq("old-refresh"), any(LocalDateTime.class))).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(enabledUser(7L));
        when(tokenCodec.issue(eq(7L), anyString(), anyInt()))
                .thenReturn(new TokenCodec.AuthToken("new-access", Instant.now().plusSeconds(3600)));
        when(refreshTokenService.issue(eq(7L), any(LocalDateTime.class))).thenReturn("new-refresh");

        AuthService.LoginResult result = authService.refresh("old-refresh");

        assertThat(result.token()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.userId()).isEqualTo(7L);
        // 旋转之后必须签发新的刷新令牌（旋转 = 一次一换）
        verify(refreshTokenService).issue(eq(7L), any(LocalDateTime.class));
    }

    /**
     * 被禁用的账号不能用刷新令牌续期：禁用必须能终止续期，
     * 否则"禁用账号"对还揣着有效刷新令牌的人不起作用。
     */
    @Test
    void refreshShouldRejectDisabledUser() {
        when(refreshTokenService.rotate(anyString(), any(LocalDateTime.class))).thenReturn(7L);
        User disabled = enabledUser(7L);
        disabled.setStatus(0);
        when(userMapper.selectById(7L)).thenReturn(disabled);

        assertThatThrownBy(() -> authService.refresh("old-refresh"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.FORBIDDEN.getCode());
        // 拒绝续期时不能再签发新的刷新令牌
        verify(refreshTokenService, never()).issue(anyLong(), any());
    }

    /**
     * 用户已被删除：按"登录过期"处理，而不是 500。
     */
    @Test
    void refreshShouldDegradeWhenUserMissing() {
        when(refreshTokenService.rotate(anyString(), any(LocalDateTime.class))).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh("old-refresh"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    /**
     * 空刷新令牌是参数错误，不应打到存储层。
     */
    @Test
    void refreshShouldRejectBlankToken() {
        assertThatThrownBy(() -> authService.refresh(" "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.PARAM_INVALID.getCode());
        verify(refreshTokenService, never()).rotate(anyString(), any());
    }

    /**
     * 登出委托给吊销（幂等语义在存储层）。
     */
    @Test
    void logoutShouldDelegateToRevoke() {
        when(refreshTokenService.revoke("some-refresh")).thenReturn(true);

        authService.logout("some-refresh");

        verify(refreshTokenService).revoke("some-refresh");
    }

    private User enabledUser(long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("alice");
        user.setStatus(1);
        user.setUserType(1);
        return user;
    }
}
