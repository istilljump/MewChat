package com.mewchat.service;

import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 刷新令牌的旋转/复用/登出生命周期验证（需要真实 MySQL，已执行 sql/10）。
 *
 * <p>旋转的核心是"旧令牌一次性作废"：它靠<b>条件更新</b>（只有 deleted=0 的行
 * 才能置 1）实现并发复用防护 —— 这类行为 Mock 验证不了（Mock 的 Mapper 不做
 * 逻辑删除过滤，见阶段 11 的教训），必须真库验证。账号被禁用不能续期的
 * 决策逻辑由不需要数据库的 {@code AuthServiceRefreshTest} 覆盖，两边互补。
 *
 * <p>本测试整体在事务内、回滚收尾，refresh_token 表零残留；
 * 登录用的是种子演示账号（sql/06，alice / 123456）。
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${mewchat.it.mysql.username:root}",
        "spring.datasource.password=${mewchat.it.mysql.password:}",
        "spring.datasource.url=${mewchat.it.mysql.url:jdbc:mysql://127.0.0.1:3306/mewchat"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        // 令牌密钥必须显式提供：application.yml 故意没有默认值（见该处注释）
        "mewchat.auth.token-secret=it-test-secret-0123456789abcdef"
})
@EnabledIfSystemProperty(named = "mewchat.it.mysql", matches = "true")
@Transactional
class MysqlRefreshTokenIntegrationTest {

    @Autowired
    private AuthService authService;

    /**
     * 完整生命周期：登录 → 旋转 → 新令牌可用、旧令牌作废 → 登出 → 续期被拒。
     */
    @Test
    void rotationLifecycleShouldWork() {
        AuthService.LoginResult login = authService.login("alice", "123456");
        assertThat(login.refreshToken()).as("登录必须同时下发刷新令牌").isNotBlank();

        // 第一次旋转：拿到新令牌对，旧的刷新令牌作废
        AuthService.LoginResult first = authService.refresh(login.refreshToken());
        assertThat(first.refreshToken())
                .as("旋转必须下发新的刷新令牌")
                .isNotBlank()
                .isNotEqualTo(login.refreshToken());
        assertThat(first.token()).isNotBlank();
        assertThat(first.userId()).isEqualTo(login.userId());

        // 第二次旋转用第一轮的新令牌：链路可以一直续下去
        AuthService.LoginResult second = authService.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // 登出：吊销当前刷新令牌
        authService.logout(second.refreshToken());
        assertThatThrownBy(() -> authService.refresh(second.refreshToken()))
                .as("登出后的刷新令牌不能再用")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.UNAUTHORIZED.getCode());

        // 登出幂等：重复登出不抛异常
        authService.logout(second.refreshToken());
        authService.logout(null);
    }

    /**
     * 旧刷新令牌复用必须被拒：旋转后的旧值哪怕还没过期也不能再换新对。
     *
     * <p>这是"被盗令牌在主人续期后立即失效"的机制 —— 旋转的防复用语义。
     */
    @Test
    void reusedRefreshTokenShouldBeRejected() {
        AuthService.LoginResult login = authService.login("alice", "123456");
        String oldToken = login.refreshToken();

        authService.refresh(oldToken);

        assertThatThrownBy(() -> authService.refresh(oldToken))
                .as("旋转后的旧刷新令牌是失效凭据，复用必须被拒")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }

    /**
     * 无效令牌（不存在的值）统一按"登录过期"拒绝，不泄露存在性。
     */
    @Test
    void garbageTokenShouldBeRejectedAsUnauthorized() {
        assertThatThrownBy(() -> authService.refresh("totally-not-a-token"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ResultCode.UNAUTHORIZED.getCode());
    }
}
