package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.common.security.TokenCodec;
import com.mewchat.config.AuthProperties;
import com.mewchat.dao.mysql.entity.User;
import com.mewchat.dao.mysql.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 认证服务：校验凭证并签发访问令牌。
 *
 * <p>本类只做"证明你是你"这一件事，不涉及授权（谁能访问什么由
 * {@code config.SecurityConfig} 的路径规则决定）。
 *
 * <p><b>为什么不区分"用户不存在"和"密码错误"</b>：两种情况返回同一句提示，
 * 是为了不让攻击者用登录接口枚举出哪些用户名真实存在 ——
 * 一旦能区分，攻击者就能先攒出一份有效用户名清单，再对这份清单做撞库。
 * 服务端日志里仍然记录真实原因，便于运维排查。
 *
 * @author MewChat
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 凭证错误的统一提示，见类注释 */
    private static final String CREDENTIAL_ERROR = "用户名或密码不正确";

    /** 用户状态：禁用 */
    private static final int STATUS_DISABLED = 0;

    /**
     * 用户不存在时用来"陪跑"一次哈希比对的固定密文。
     *
     * <p><b>不能直接把 null 交给 {@code PasswordEncoder.matches}</b>：
     * BCrypt 实现看到空密文会立刻返回 false（还会打一条 warning），根本不进哈希计算，
     * 于是"用户不存在"只需 1ms 左右、"密码错误"要走完整轮 BCrypt 要几十毫秒 ——
     * 攻击者据此能把有效用户名一个个筛出来，正是上面那段注释想避免的事。
     * 给一个真实的 BCrypt 密文就能保证两种情况的计算量相当。
     *
     * <p>这个密文对应的是一个随机密码，任何输入都不会匹配成功，因此它只用于消耗时间。
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserMapper userMapper;

    private final TokenCodec tokenCodec;

    private final PasswordEncoder passwordEncoder;

    private final RefreshTokenService refreshTokenService;

    private final AuthProperties authProperties;

    public AuthService(UserMapper userMapper, TokenCodec tokenCodec, PasswordEncoder passwordEncoder,
                       RefreshTokenService refreshTokenService, AuthProperties authProperties) {
        this.userMapper = userMapper;
        this.tokenCodec = tokenCodec;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.authProperties = authProperties;
    }

    /**
     * 校验用户名密码并签发令牌。
     *
     * @param username 用户名
     * @param password 明文密码，仅在本方法内存活
     * @return 登录结果，含令牌与用户展示信息
     * @throws BizException 凭证错误或账号被禁用时抛出
     */
    public LoginResult login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BizException(ResultCode.PARAM_INVALID, "用户名与密码不能为空");
        }

        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username.trim()));

        // 密码校验放在分支外而不是短路求值：即使 user 为空也真跑一次哈希比较，
        // 让"用户不存在"与"密码错误"的响应耗时接近，避免通过响应时间区分两者。
        // 用户不存在时用固定的陪跑密文 —— 传 null 的话 BCrypt 会直接返回，
        // 时间差反而把"这个用户名不存在"暴露得更明显
        String storedHash = user == null ? DUMMY_PASSWORD_HASH : user.getPassword();
        boolean passwordMatched = StringUtils.hasText(storedHash)
                && passwordEncoder.matches(password, storedHash);
        if (user == null || !passwordMatched) {
            log.warn("登录失败：username={} 原因={}", username, user == null ? "用户不存在" : "密码错误");
            throw new BizException(ResultCode.UNAUTHORIZED, CREDENTIAL_ERROR);
        }

        if (user.getStatus() != null && user.getStatus() == STATUS_DISABLED) {
            log.warn("登录失败：账号已被禁用 userId={}", user.getId());
            throw new BizException(ResultCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        updateLastLoginTime(user.getId());

        // 用户类型缺失时按最小权限处理，不能落到管理员
        int userType = user.getUserType() == null
                ? AuthenticatedUser.TYPE_CUSTOMER
                : user.getUserType();

        TokenCodec.AuthToken issued = tokenCodec.issue(user.getId(), user.getUsername(), userType);
        log.info("登录成功：userId={} username={} userType={}", user.getId(), user.getUsername(), userType);

        // 刷新令牌与访问令牌同时签发：前者可吊销、负责"续期与登出"，
        // 后者无状态、负责"每个请求的身份证明"。分工见 RefreshTokenService 注释
        LocalDateTime refreshExpire = LocalDateTime.now().plusDays(authProperties.getRefreshTokenDays());
        String refreshToken = refreshTokenService.issue(user.getId(), refreshExpire);

        return new LoginResult(issued.token(), issued.expiresAt(), refreshToken,
                refreshExpire, user.getId(), user.getUsername(), user.getNickname());
    }

    /**
     * 用刷新令牌换新令牌对（旋转）。
     *
     * <p>旧刷新令牌一次性作废、签发新的刷新令牌与访问令牌。
     * 归属用户已不存在或被禁用时拒绝刷新 —— 禁用必须能终止续期，
     * 否则"禁用账号"对还揣着有效刷新令牌的人不起作用。
     *
     * @param refreshToken 刷新令牌原文
     * @return 新的令牌对与用户展示信息
     * @throws BizException 刷新令牌无效/已旋转/已登出/已过期，或账号不可用时抛出
     */
    public LoginResult refresh(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BizException(ResultCode.PARAM_INVALID, "refreshToken 不能为空");
        }

        Long userId = refreshTokenService.rotate(refreshToken,
                LocalDateTime.now().plusDays(authProperties.getRefreshTokenDays()));

        User user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("刷新被拒：用户不存在 userId={}", userId);
            throw new BizException(ResultCode.UNAUTHORIZED, "登录状态已过期，请重新登录");
        }
        if (user.getStatus() != null && user.getStatus() == STATUS_DISABLED) {
            log.warn("刷新被拒：账号已被禁用 userId={}", userId);
            throw new BizException(ResultCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        int userType = user.getUserType() == null
                ? AuthenticatedUser.TYPE_CUSTOMER
                : user.getUserType();
        TokenCodec.AuthToken issued = tokenCodec.issue(user.getId(), user.getUsername(), userType);
        log.info("刷新令牌旋转成功：userId={}", user.getId());

        LocalDateTime refreshExpire = LocalDateTime.now().plusDays(authProperties.getRefreshTokenDays());
        String newRefreshToken = refreshTokenService.issue(user.getId(), refreshExpire);
        return new LoginResult(issued.token(), issued.expiresAt(), newRefreshToken,
                refreshExpire, user.getId(), user.getUsername(), user.getNickname());
    }

    /**
     * 登出：吊销刷新令牌（幂等）。
     *
     * <p>访问令牌是<b>吊销不了的</b>（无状态，见 TokenCodec 的已知取舍），
     * 登出后它在有效期内仍可用。这是刻意接受的取舍：登出主要保障"不再能续期"，
     * 把长期凭据的暴露窗口从"7 天"收窄到"访问令牌剩余有效期（默认至多 2 小时）"。
     * 需要立即生效的强制下线仍然只有换密钥一条路。
     *
     * @param refreshToken 刷新令牌原文，可为空（幂等）
     */
    public void logout(String refreshToken) {
        boolean revoked = refreshTokenService.revoke(refreshToken);
        log.info("登出：{}（幂等）", revoked ? "已吊销刷新令牌" : "无有效刷新令牌");
    }

    /**
     * 记录最后登录时间。
     *
     * <p>失败只记日志：这只是审计信息，为它中断一次成功的登录不值得。
     *
     * @param userId 用户ID
     */
    private void updateLastLoginTime(Long userId) {
        try {
            // 只带主键与要改的字段：MyBatis-Plus 只更新非 null 字段，
            // 传整个实体会把从库里读到的其他值一起写回去，覆盖掉并发修改
            User patch = new User();
            patch.setId(userId);
            patch.setLastLoginTime(LocalDateTime.now());
            userMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("更新最后登录时间失败：userId={}", userId, e);
        }
    }

    /**
     * 登录/刷新的结果。
     *
     * <p>只带接口层需要的东西，不把 {@link User} 实体透出去 ——
     * 实体里有密码哈希，一旦被序列化到响应里就是一次凭据泄露。
     *
     * @param token             访问令牌
     * @param expiresAt         访问令牌过期时间
     * @param refreshToken      刷新令牌（可吊销，用于静默续期与登出）
     * @param refreshExpiresAt  刷新令牌过期时间
     * @param userId            用户ID
     * @param username          用户名
     * @param nickname          昵称
     */
    public record LoginResult(String token,
                              Instant expiresAt,
                              String refreshToken,
                              LocalDateTime refreshExpiresAt,
                              Long userId,
                              String username,
                              String nickname) {
    }
}
