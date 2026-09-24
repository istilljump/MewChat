package com.mewchat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SSE 流式输出配置。
 *
 * <p>SSE 需要两样东西，本类都提供：
 * <ol>
 *     <li><b>超时时间</b> —— 不配置的话会用容器默认值，长回答会被中途掐断</li>
 *     <li><b>独立线程池</b> —— 流式任务是长任务，若占用 Tomcat 的工作线程，
 *         几个并发会话就能把整个服务堵死。SSE 的异步模型会把请求线程释放回容器，
 *         真正的等待发生在这个独立线程池里</li>
 * </ol>
 *
 * <p>业务层使用方式：Controller 返回 {@code SseEmitter}，
 * 在 {@link #sseTaskExecutor()} 上执行流式任务并通过 emitter 推送分片。
 *
 * @author MewChat
 */
@Configuration
public class SseConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SseConfig.class);

    private final SseProperties sseProperties;

    public SseConfig(SseProperties sseProperties) {
        this.sseProperties = sseProperties;
    }

    /**
     * 注册异步请求支持。
     *
     * <p>设置的是"异步请求"的默认超时与执行器，作用于所有返回
     * {@code SseEmitter} / {@code DeferredResult} 的接口。
     * 单个接口若需要不同的超时，可在创建 {@code SseEmitter} 时传入自己的超时值覆盖此处。
     *
     * @param configurer 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(sseProperties.getTimeoutMs());
        configurer.setTaskExecutor(sseTaskExecutor());
        log.info("SSE 异步支持已配置：timeout={}ms 线程池 core={} max={} queue={}",
                sseProperties.getTimeoutMs(), sseProperties.getCorePoolSize(),
                sseProperties.getMaxPoolSize(), sseProperties.getQueueCapacity());
    }

    /**
     * 流式任务专用线程池。
     *
     * <p>参数取舍：
     * <ul>
     *     <li>队列有界（{@code queueCapacity}）—— 无界队列在过载时只会堆积任务、
     *         让所有用户一起变慢，有界队列能让过载被快速发现</li>
     *     <li>拒绝策略用默认的 Abort —— 队列满时直接抛异常，由全局异常处理器
     *         返回"系统繁忙"。这比让请求无限等待更诚实</li>
     *     <li>关闭时等待任务收尾 —— 避免停机瞬间把正在输出的回答腰斩</li>
     * </ul>
     *
     * <p>容量需按预期的并发会话数评估：最大并发流式会话数约为
     * {@code maxPoolSize + queueCapacity}。
     *
     * @return 流式任务线程池
     */
    @Bean("sseTaskExecutor")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(sseProperties.getCorePoolSize());
        executor.setMaxPoolSize(sseProperties.getMaxPoolSize());
        executor.setQueueCapacity(sseProperties.getQueueCapacity());
        executor.setThreadNamePrefix(sseProperties.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
