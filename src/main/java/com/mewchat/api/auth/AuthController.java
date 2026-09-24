package com.mewchat.api.auth;

import com.mewchat.api.auth.dto.LoginRequest;
import com.mewchat.api.auth.dto.LoginResponse;
import com.mewchat.common.result.Result;
import com.mewchat.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录，换取访问令牌。
     *
     * <p>凭证错误时抛出的业务异常由全局异常处理器转成统一响应
     * （{@code code=20001}），因此这里不需要 try-catch，
     * <b>也不会把"是用户名错还是密码错"透给调用方</b>。
     *
     * @param request 登录请求
     * @return 令牌与用户展示信息
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());

        // 不记录令牌本身：日志往往会被集中收集、长期保存、多人可读，
        // 把令牌写进去等于把它泄露给所有能看日志的人
        log.info("签发访问令牌：userId={} 有效期至 {}", result.userId(), result.expiresAt());

        return Result.success(new LoginResponse(
                result.token(),
                result.expiresAt(),
                result.userId(),
                result.username(),
                result.nickname()));
    }
}
