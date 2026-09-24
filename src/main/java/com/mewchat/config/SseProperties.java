package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSE 流式输出配置，对应 {@code application.yml} 的 {@code mewchat.sse.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.sse")
public class SseProperties {

    /**
     * 异步请求超时时间（毫秒）。
     *
     * <p>这是 SSE 连接的最长存活时间，超时后容器会关闭连接。
     * 注意它和 {@code server.tomcat.connection-timeout} 不是一回事：
     * 后者只约束"建立连接后等待请求头"的时长，与长连接无关。
     *
     * <p>取值需要覆盖一次完整回答（含多轮工具调用）的耗时，
     * 默认 5 分钟，可按实际模型响应速度调整。
     */
    private long timeoutMs = 300_000L;

    /** 流式任务线程池核心线程数 */
    private int corePoolSize = 4;

    /** 流式任务线程池最大线程数，需按预期并发会话数评估 */
    private int maxPoolSize = 16;

    /** 流式任务等待队列容量 */
    private int queueCapacity = 100;

    /** 线程名前缀，便于在 jstack / 日志中定位流式任务 */
    private String threadNamePrefix = "mewchat-sse-";
}
