package com.mewchat.service;

import com.mewchat.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内限流：登录防爆破 + 对话接口按用户限速。
 *
 * <p>两个限流器共用"内存计数"这一个实现，但语义不同，别混：
 * <ul>
 *     <li><b>登录防爆破</b>是"连续失败计数 + 临时锁定"：连续错 N 次锁 M 分钟，
 *         成功登录即清零。防的是拿字典撞密码 —— 锁定让爆破从"每秒千次"变成"每十分钟五次"，
 *         等不起的是攻击者。</li>
 *     <li><b>对话限速</b>是"固定窗口计数"：每用户每分钟最多 N 轮。防的是脚本刷接口
 *         —— 每轮对话背后是多次模型调用，token 成本真实存在。</li>
 * </ul>
 *
 * <p><b>单实例内存实现，多实例部署需要换 Redis</b>（计数各自为政等于限流乘以实例数）。
 * 本项目当前是单机演示形态，内存实现的零依赖与确定性足够；换成分布式存储时
 * 对外方法签名不变，调用方无感。
 *
 * <p><b>锁定的两把钥匙</b>：只锁"用户名"会误伤（主人手滑五次就把真人锁在外面），
 * 只锁"用户名+IP"可被换 IP 绕过。两把都锁 —— 误伤者换台设备或等十分钟就能进，
 * 爆破者要同时换 IP 且忍受用户级阈值。
 *
 * @author MewChat
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** 窗口长度固定为一分钟（毫秒） */
    private static final long WINDOW_MILLIS = 60_000L;

    private final RateLimitProperties properties;

    /** 对话限速：key → (窗口号, 计数) */
    private final ConcurrentHashMap<String, WindowCounter> chatWindows = new ConcurrentHashMap<>();

    /** 登录失败：key → (连续失败次数, 锁定截止时间戳) */
    private final ConcurrentHashMap<String, LoginState> loginStates = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * 登录是否处于锁定期。
     *
     * @param usernameKey 用户级钥匙（归一化用户名）
     * @param ipKey       用户名+来源IP 级钥匙
     * @return true 表示至少一把钥匙在锁定期内，应直接拒绝
     */
    public boolean isLoginLocked(String usernameKey, String ipKey) {
        if (!properties.isEnabled()) {
            return false;
        }
        return isLocked(usernameKey) || isLocked(ipKey);
    }

    /**
     * 记一次登录失败（用户级与用户名+IP 级同时累计）。
     *
     * @param usernameKey 用户级钥匙
     * @param ipKey       用户名+来源IP 级钥匙
     */
    public void recordLoginFailure(String usernameKey, String ipKey) {
        if (!properties.isEnabled()) {
            return;
        }
        recordFailure(usernameKey);
        recordFailure(ipKey);
        LoginState state = loginStates.get(usernameKey);
        if (state != null && state.failures.get() >= properties.getLoginMaxFailures()) {
            log.warn("登录失败次数达到上限，临时锁定：key={}", usernameKey);
        }
    }

    /** 登录成功，清空该用户名相关钥匙的失败计数。 */
    public void resetLoginFailures(String usernameKey, String ipKey) {
        loginStates.remove(usernameKey);
        loginStates.remove(ipKey);
    }

    /**
     * 对话接口限速判断：本窗口内还有没有余量。
     *
     * <p>固定窗口的已知取舍：窗口边界两侧各打一半可以做到实际 2N 轮 ——
     * 对"防脚本"这个目的足够；精确滑动窗口的复杂度在这里不划算。
     *
     * @param userKey 用户级钥匙（如 "user:123"）
     * @return true 表示放行（并占用一个名额），false 表示本窗口已满
     */
    public boolean allowChat(String userKey) {
        if (!properties.isEnabled()) {
            return true;
        }
        long window = System.currentTimeMillis() / WINDOW_MILLIS;
        WindowCounter counter = chatWindows.computeIfAbsent(userKey, k -> new WindowCounter());
        int limit = Math.max(1, properties.getChatRequestsPerMinute());
        return counter.tryAcquire(window, limit);
    }

    private boolean isLocked(String key) {
        LoginState state = loginStates.get(key);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.lockedUntil == 0) {
            return false;
        }
        if (state.lockedUntil <= now) {
            // 锁定期刚过：顺手清掉失败计数，下次从零开始。
            // 注意不能碰"从未锁定"的状态（lockedUntil==0）—— 那是失败累计中的记录，
            // 每次登录前的 isLocked 查询都会走到这里，清掉它等于清零失败计数
            loginStates.remove(key, state);
            return false;
        }
        return true;
    }

    private void recordFailure(String key) {
        long now = System.currentTimeMillis();
        loginStates.compute(key, (k, state) -> {
            if (state == null) {
                state = new LoginState();
            } else if (state.lockedUntil > 0 && state.lockedUntil <= now) {
                // 上一轮锁定期刚过又来失败：重新计数
                state = new LoginState();
            }
            state.failures.incrementAndGet();
            if (state.failures.get() >= properties.getLoginMaxFailures()) {
                state.lockedUntil = now + properties.getLoginLockMinutes() * 60_000L;
            }
            return state;
        });
    }

    /** 固定窗口计数器：窗口号变了计数归零 */
    private static final class WindowCounter {
        private final AtomicLong window = new AtomicLong(-1);
        private final AtomicLong count = new AtomicLong();

        boolean tryAcquire(long windowId, int limit) {
            // 同一窗口内自旋重试；窗口切换时只有一个线程能重置成功
            while (true) {
                long current = window.get();
                if (current == windowId) {
                    if (count.incrementAndGet() > limit) {
                        return false;
                    }
                    return true;
                }
                if (window.compareAndSet(current, windowId)) {
                    count.set(1);
                    if (limit < 1) {
                        return false;
                    }
                    return true;
                }
            }
        }
    }

    /** 登录失败状态：连续失败次数 + 锁定截止时间（0 = 未锁定） */
    private static final class LoginState {
        private final AtomicLong failures = new AtomicLong();
        private volatile long lockedUntil;
    }
}
