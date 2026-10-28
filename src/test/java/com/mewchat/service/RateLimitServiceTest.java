package com.mewchat.service;

import com.mewchat.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RateLimitService} 的单元测试（无依赖、无时间等待）。
 *
 * <p>接口级行为（锁定期内拒绝、成功清零）由 {@code LoginLockoutIntegrationTest} /
 * {@code RateLimitIntegrationTest} 承担；本类钉的是服务自身的计数语义与开关。
 *
 * @author MewChat
 */
class RateLimitServiceTest {

    private static RateLimitProperties properties(int maxFailures, int perMinute, boolean enabled) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginMaxFailures(maxFailures);
        properties.setChatRequestsPerMinute(perMinute);
        properties.setEnabled(enabled);
        return properties;
    }

    /**
     * 连续失败达到阈值后锁定，锁定期内 isLoginLocked 为真；成功清零后解锁。
     */
    @Test
    void loginShouldLockAfterConsecutiveFailuresAndResetOnSuccess() {
        RateLimitService service = new RateLimitService(properties(2, 10, true));

        assertThat(service.isLoginLocked("u:alice", "uip:alice|1.1.1.1")).isFalse();

        service.recordLoginFailure("u:alice", "uip:alice|1.1.1.1");
        assertThat(service.isLoginLocked("u:alice", "uip:alice|1.1.1.1")).isFalse();

        service.recordLoginFailure("u:alice", "uip:alice|1.1.1.1");
        assertThat(service.isLoginLocked("u:alice", "uip:alice|1.1.1.1"))
                .as("第 2 次失败达到阈值，两把钥匙都应处于锁定期")
                .isTrue();

        service.resetLoginFailures("u:alice", "uip:alice|1.1.1.1");
        assertThat(service.isLoginLocked("u:alice", "uip:alice|1.1.1.1"))
                .as("成功登录清零失败计数，立即解锁")
                .isFalse();
    }

    /**
     * 锁定的两把钥匙是独立的：换 IP 只解锁"用户名+IP"那把，
     * 用户级钥匙仍然锁着 —— 换 IP 绕不过去。
     */
    @Test
    void usernameKeyShouldStayLockedWhenIpChanges() {
        RateLimitService service = new RateLimitService(properties(2, 10, true));

        service.recordLoginFailure("u:bob", "uip:bob|1.1.1.1");
        service.recordLoginFailure("u:bob", "uip:bob|1.1.1.1");

        assertThat(service.isLoginLocked("u:bob", "uip:bob|2.2.2.2"))
                .as("换了 IP，但用户级钥匙仍在锁定期")
                .isTrue();
    }

    /**
     * 对话限速的窗口边界：配额用满即拒，不同用户互不影响。
     */
    @Test
    void chatShouldLimitPerUserPerWindow() {
        RateLimitService service = new RateLimitService(properties(5, 2, true));

        assertThat(service.allowChat("user:1")).isTrue();
        assertThat(service.allowChat("user:1")).isTrue();
        assertThat(service.allowChat("user:1"))
                .as("同窗口内第 3 次应被拒")
                .isFalse();
        assertThat(service.allowChat("user:2"))
                .as("另一个用户的配额是独立的")
                .isTrue();
    }

    /**
     * 关闭开关后一切放行：压测等场景的逃生口。
     */
    @Test
    void disabledShouldBypassAllLimits() {
        RateLimitService service = new RateLimitService(properties(1, 1, false));

        assertThat(service.allowChat("user:1")).isTrue();
        assertThat(service.allowChat("user:1")).isTrue();
        assertThat(service.isLoginLocked("u:alice", "uip:alice|1.1.1.1")).isFalse();
        service.recordLoginFailure("u:alice", "uip:alice|1.1.1.1");
        assertThat(service.isLoginLocked("u:alice", "uip:alice|1.1.1.1")).isFalse();
    }
}
