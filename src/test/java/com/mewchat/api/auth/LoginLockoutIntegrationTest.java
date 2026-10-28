package com.mewchat.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 登录防爆破的接口级测试：失败阈值刻意调小（连续 2 次锁定 10 分钟），
 * 让"锁定"能在一两条请求内触发。
 *
 * <p>锁定状态是进程内存、跨用例共享的，因此每条用例用<b>独立的用户名</b>，互不串味。
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mewchat_lock;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "mewchat.auth.token-secret=lock-test-secret-0123456789abcdef",
        "mewchat.rate-limit.login-max-failures=2",
        "mewchat.rate-limit.login-lock-minutes=10"
})
@AutoConfigureMockMvc
class LoginLockoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 凭证校验换成替身：锁定行为发生在控制器，不该依赖真实数据库 */
    @MockitoBean
    private AuthService authService;

    /**
     * 连续失败达到阈值后，锁定期内直接拒绝（20003），不再打到凭证校验。
     *
     * <p>第 3 次请求故意把凭证"修正"为正确的 —— 仍然被拒：
     * 锁定看的是失败记录，不是凭证本身。
     */
    @Test
    void loginShouldBeLockedAfterConsecutiveFailures() throws Exception {
        willThrow(new BizException(ResultCode.UNAUTHORIZED, "用户名或密码不正确"))
                .given(authService).login(anyString(), anyString());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(login("lockme", "wrong"))
                    .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
        }

        // 第 3 次：锁定期内，根本不会走到凭证校验 —— 桩保持"永远失败"，
        // 若流程真的去碰了凭证校验，本用例会收到 20001 而不是 20003（照样红）
        mockMvc.perform(login("lockme", "right"))
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));
    }

    /**
     * 登录成功即清零失败计数：错一次、对一次、再错一次 —— 不会被锁定。
     *
     * <p>这是"连续失败"语义的关键：累计的是连续的坏尝试，不是历史总账。
     * 若把历史总错次数当依据，共享出口 IP 的办公网络很快就把所有账号锁死了。
     */
    @Test
    void loginFailureCounterShouldResetOnSuccess() throws Exception {
        var password = new AtomicReference<>("wrong");
        willAnswer(invocation -> "wrong".equals(password.get())
                ? credentialError()
                : successResult()).given(authService).login(anyString(), anyString());

        mockMvc.perform(login("resetme", "wrong"))
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));

        password.set("right");
        mockMvc.perform(login("resetme", "right"))
                .andExpect(jsonPath("$.code").value(0));

        // 失败计数已被成功清零：再错一次仍是凭证错误（20001），而不是锁定（20003）
        password.set("wrong");
        mockMvc.perform(login("resetme", "wrong"))
                .andExpect(jsonPath("$.code").value(ResultCode.UNAUTHORIZED.getCode()));
    }

    /* ==================== 辅助 ==================== */

    private AuthService.LoginResult credentialError() {
        throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码不正确");
    }

    private AuthService.LoginResult successResult() {
        return new AuthService.LoginResult("token-for-test",
                java.time.Instant.parse("2099-01-01T00:00:00Z"),
                "refresh-for-test", java.time.LocalDateTime.now().plusDays(7),
                1L, "u", "n");
    }

    private MockHttpServletRequestBuilder login(String username, String secret) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(
                        Map.of("username", username, "password", secret)));
    }
}
