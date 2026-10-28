package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 接口限流配置，对应 {@code application.yml} 的 {@code mewchat.rate-limit.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.rate-limit")
public class RateLimitProperties {

    /**
     * 是否启用限流。
     *
     * <p>默认开启。关闭的唯一场景是压测时不想让限流干扰数据。
     */
    private boolean enabled = true;

    /**
     * 登录连续失败多少次后锁定。
     *
     * <p>5 次是常见起点：太小会误伤手滑的正常用户（他们还有"稍后再试"兜底），
     * 太大则爆破成本可接受。锁定的对象是"用户名"与"用户名+来源IP"两把钥匙，
     * 见 {@code RateLimitService} 的说明。
     */
    private int loginMaxFailures = 5;

    /**
     * 登录锁定持续多少分钟。
     *
     * <p>锁定是临时的而不是永久的：永久锁定给了攻击者"锁死别人的账号"的武器
     * （故意输错五次让主人永远登不上），临时锁定让爆破者等不起、让误伤者等得起。
     */
    private int loginLockMinutes = 10;

    /**
     * 每个用户每分钟允许发起的对话轮数。
     *
     * <p>每轮对话背后是意图识别 + 回复生成（可能还有补检索、工具循环）的多次
     * 模型调用，token 成本真实存在。10 轮/分钟远超正常人的手速，
     * 足以拦住脚本刷接口。
     */
    private int chatRequestsPerMinute = 10;
}
