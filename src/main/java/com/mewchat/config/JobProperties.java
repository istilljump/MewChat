package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务配置，对应 {@code application.yml} 的 {@code mewchat.job.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.job")
public class JobProperties {

    /** 会话收尾任务配置 */
    private SessionClose sessionClose = new SessionClose();

    /** 问题池聚类任务配置 */
    private Clustering clustering = new Clustering();

    /**
     * 会话收尾任务配置。
     *
     * <p>这个任务是"长时记忆"的触发源：会话不结束就不会生成摘要，
     * 没有摘要，超过窗口的早期对话内容就永久丢失了。
     */
    @Getter
    @Setter
    public static class SessionClose {

        /**
         * 是否启用。
         *
         * <p>默认开启。注意本任务会调用大模型生成摘要，会产生 token 消耗，
         * 需要用真实密钥运行又不想产生费用时可临时关闭。
         */
        private boolean enabled = true;

        /**
         * 空闲多久视为会话已结束（分钟）。
         *
         * <p>取值要略大于用户可能的"中途思考"时间：设太小会把正在进行的对话
         * 提前掐断并生成半截摘要；设太大则摘要迟迟不生成。
         */
        private int idleMinutes = 30;

        /** 单批处理条数上限 */
        private int batchSize = 50;

        /** 两次执行之间的间隔（毫秒），默认 10 分钟 */
        private long intervalMs = 600_000L;

        /** 应用启动后首次执行的延迟（毫秒），默认 1 分钟 */
        private long initialDelayMs = 60_000L;
    }

    /**
     * 问题池聚类任务配置（数据飞轮的后半段）。
     *
     * <p>把"问法不同、问的是同一件事"的问题归成簇，产出运营能直接看的待优化清单。
     */
    @Getter
    @Setter
    public static class Clustering {

        /** 是否启用。纯本地计算，不调用大模型，没有 token 费用 */
        private boolean enabled = true;

        /**
         * 单次处理条数上限。
         *
         * <p>聚类是两两比较（贪心算法对每个问题遍历已有簇），复杂度随规模增长。
         * 封顶既是为了任务时长可控，也避免池子被灌爆时把数据库与内存压垮。
         * 池子超过这个数时，每轮只处理"被问得最多的前 N 条"，剩下的等下一轮。
         */
        private int batchSize = 500;

        /**
         * 相似度阈值（0~1，字面 2-gram 的 Jaccard 系数）。
         *
         * <p>调高 → 归并更保守（只把问法几乎一样的归到一起）；
         * 调低 → 归并更激进（可能把不同的问题混进一簇）。
         * 这个值必须拿真实问题池调，默认值只是起点。
         */
        private double similarityThreshold = 0.55;

        /** 两次执行之间的间隔（毫秒），默认一天 */
        private long intervalMs = 86_400_000L;

        /** 应用启动后首次执行的延迟（毫秒），默认 10 分钟（避开启动高峰） */
        private long initialDelayMs = 600_000L;
    }
}
