package com.mewchat.job;

import com.mewchat.service.QuestionClusteringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 问题池聚类定时任务：每天把"问法不同、问的是同一件事"的问题归成簇。
 *
 * <p><b>本类只是调度壳子</b>，聚类的全部逻辑在
 * {@link QuestionClusteringService}。分开的理由是它有两个触发源 ——
 * 每日定时，以及运营在后台点"立即重算"（攒了一批新问题就不必等到第二天）。
 * 逻辑写在任务里的话，后台接口就得反过来依赖定时任务，
 * 把"什么时候跑"和"跑什么"耦合在一起。
 *
 * <p><b>纯本地计算，不调用大模型</b>：没有 token 费用，
 * 因此也不需要像会话摘要那样在缺密钥时关掉。
 *
 * @author MewChat
 */
@Component
@ConditionalOnProperty(prefix = "mewchat.job.clustering", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class QuestionClusteringJob {

    private static final Logger log = LoggerFactory.getLogger(QuestionClusteringJob.class);

    private final QuestionClusteringService clusteringService;

    public QuestionClusteringJob(QuestionClusteringService clusteringService) {
        this.clusteringService = clusteringService;
    }

    /**
     * 对问题池做一次聚类。
     *
     * <p>用 fixedDelay 而不是 cron：相邻两次执行之间留出间隔，
     * 避免上一轮还没跑完就被再次触发。默认间隔一天。
     */
    @Scheduled(
            fixedDelayString = "${mewchat.job.clustering.interval-ms:86400000}",
            initialDelayString = "${mewchat.job.clustering.initial-delay-ms:600000}")
    public void clusterPendingQuestions() {
        try {
            clusteringService.clusterPendingQuestions();
        } catch (Exception e) {
            // 定时任务里抛出的异常不会有人接，只会静默消失在调度器的日志里。
            // 这里显式记下来，保证"聚类没跑成"这件事能被发现
            log.error("问题池聚类任务执行失败", e);
        }
    }
}
