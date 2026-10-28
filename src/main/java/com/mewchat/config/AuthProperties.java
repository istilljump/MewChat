package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置，对应 {@code application.yml} 的 {@code mewchat.auth.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.auth")
public class AuthProperties {

    /**
     * 令牌签名密钥。
     *
     * <p><b>必须由部署方显式提供</b>（yml 里写成 {@code ${MEWCHAT_TOKEN_SECRET:...}}），
     * 且不得使用示例值上生产：拿到密钥的人可以给任意用户签发令牌，
     * 等于拿到整个系统的身份。因此 {@code TokenCodec} 在启动时会校验它非空且足够长，
     * 宁可起不来，也不能用一个弱密钥悄悄跑起来。
     *
     * <p>换密钥会让所有已签发的令牌立即失效，这是预期的（也是唯一的"强制下线"手段）。
     */
    private String tokenSecret;

    /**
     * 令牌有效期（分钟）。
     *
     * <p>取得过长会让令牌泄露后的窗口期变长；过短则用户频繁重新登录。
     * 客服场景交互时长有限，默认 2 小时。前端应在到期前用刷新令牌静默续期。
     */
    private long tokenExpireMinutes = 120;

    /**
     * 刷新令牌有效期（天）。
     *
     * <p>刷新令牌落库、可吊销（登出/旋转即失效），比访问令牌长得多是安全的：
     * 它只在认证接口之间流转，泄露窗口有登出与旋转兜底。默认 7 天 ——
     * 与"客服工作台一天登录一次"的使用形态匹配；每次刷新都会重新计期并旋转，
     * 长期活跃的用户不需要重新登录。
     */
    private long refreshTokenDays = 7;
}
