package com.mewchat.api.auth;

import com.mewchat.api.auth.dto.LoginRequest;
import com.mewchat.api.auth.dto.LoginResponse;
import com.mewchat.api.auth.dto.LogoutRequest;
import com.mewchat.api.auth.dto.RefreshRequest;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import com.mewchat.config.RateLimitProperties;
import com.mewchat.service.AuthService;
import com.mewchat.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p><b>这是整个 api 层唯一的免认证入口</b>（见 {@code config.SecurityConfig} 的放行清单）：
 * 用户必须先登录换到令牌，才能访问对话接口。若登录本身也要求认证，就没有入口了。
 *
 * <p>本类只做参数校验与响应封装，凭证校验与令牌签发都在 {@link AuthService}。
 * 登录防爆破的计数在这里而不是服务层：锁定的钥匙要拼"来源IP"，
 * 那是 HTTP 层的信息，服务层不该认识它。
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    private final RateLimitService rateLimitService;

    private final RateLimitProperties rateLimitProperties;

    public AuthController(AuthService authService, RateLimitService rateLimitService,
                          RateLimitProperties rateLimitProperties) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 登录，换取访问令牌。
     *
     * <p>凭证错误时抛出的业务异常由全局异常处理器转成统一响应
     * （{@code code=20001}），因此这里不需要 try-catch，
     * <b>也不会把"是用户名错还是密码错"透给调用方</b>。
     *
     * <p>防爆破：锁定期内直接拒绝（如实说明"次数过多"而不是伪装成密码错误 ——
     * 伪装会让正常用户无限重试到天荒地老）；凭证错误累计失败，成功即清零。
     *
     * @param request 登录请求
     * @param request2 HTTP 请求（取来源 IP，限流钥匙的一半）
     * @return 令牌与用户展示信息
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        String usernameKey = "u:" + (request.username() == null
                ? "" : request.username().trim().toLowerCase());
        String ipKey = "uip:" + usernameKey + "|" + clientIp(httpRequest);

        if (rateLimitService.isLoginLocked(usernameKey, ipKey)) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    "登录失败次数过多，请 " + rateLimitProperties.getLoginLockMinutes() + " 分钟后再试");
        }

        AuthService.LoginResult result;
        try {
            result = authService.login(request.username(), request.password());
        } catch (BizException e) {
            // 只把"凭证错误"计入失败：参数不合法、数据库故障不该把人锁在外面。
            // 禁用账号（FORBIDDEN）也不计 —— 它已经登录不进来，无需爆破
            if (e.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
                rateLimitService.recordLoginFailure(usernameKey, ipKey);
            }
            throw e;
        }
        rateLimitService.resetLoginFailures(usernameKey, ipKey);

        // 不记录令牌本身：日志往往会被集中收集、长期保存、多人可读，
        // 把令牌写进去等于把它泄露给所有能看日志的人
        log.info("签发访问令牌：userId={} 有效期至 {}", result.userId(), result.expiresAt());

        return Result.success(new LoginResponse(
                result.token(),
                result.expiresAt(),
                result.refreshToken(),
                result.refreshExpiresAt(),
                result.userId(),
                result.username(),
                result.nickname()));
    }

    /**
     * 用刷新令牌换新令牌对（旋转）。
     *
     * <p>免认证：刷新令牌本身就是这一请求的凭证（见 {@code RefreshRequest}）。
     * 旧刷新令牌一次性作废，前端必须保存响应里下发的新令牌对 ——
     * 两者其一弄丢的后果都是"只能重新登录"。
     *
     * <p>刻意<b>不做登录防爆破的失败计数</b>：这里的失败不代表有人在对某个用户名
     * 试密码（请求里根本没有用户名），凭空计数只会给攻击者一把"锁死别人会话续期"的武器。
     *
     * @param request 刷新请求
     * @return 新的令牌对与用户展示信息
     */
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthService.LoginResult result = authService.refresh(request.refreshToken());
        return Result.success(new LoginResponse(
                result.token(),
                result.expiresAt(),
                result.refreshToken(),
                result.refreshExpiresAt(),
                result.userId(),
                result.username(),
                result.nickname()));
    }

    /**
     * 登出：吊销刷新令牌（幂等）。
     *
     * <p>免认证：刷新令牌是登出的凭证，持有它才证明"是登录的那个人在登出"。
     * 访问令牌无状态、吊销不了 —— 登出后它在剩余有效期内仍可用，
     * 这是既有无状态设计的取舍（见 {@code AuthService.logout} 注释）。
     *
     * @param request 登出请求（刷新令牌允许为空，幂等）
     * @return 空载荷的成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody LogoutRequest request) {
        authService.logout(request == null ? null : request.refreshToken());
        return Result.success(null);
    }

    /**
     * 取调用方来源 IP。
     *
     * <p>优先 X-Forwarded-For 的第一段（经过反向代理时它才是真实客户端），
     * 取不到退化到直连地址。头是可伪造的，但这只影响"爆破者给自己换个钥匙"，
     * 用户级钥匙始终有效 —— 与 {@code RateLimitService} 的双层钥匙设计配套。
     *
     * @param request HTTP 请求
     * @return 来源 IP；取不到时返回 "unknown"
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return StringUtils.hasText(remote) ? remote : "unknown";
    }
}
