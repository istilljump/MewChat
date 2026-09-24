package com.mewchat.service;

import com.mewchat.common.util.QuestionClusterer;
import com.mewchat.config.JobProperties;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问题池聚类服务（数据飞轮的后半段）。
 *
 * <p>把"问法不同、问的是同一件事"的问题归成簇，并把结果写回问题池。
 * 问题池在写入时已按归一化哈希做过一次聚合，但那只能合并<b>完全相同的问法</b>；
 * 运营真正要看的是主题（"最近有 20 个人都在问赠品什么时候发货"），
 * 这 20 条问法各不相同，得靠聚类归并。
 *
 * <p>抽出成服务而不是留在定时任务里，是因为它有两个触发源：
 * <b>每日定时</b>（{@code QuestionClusteringJob}）与<b>运营手动重算</b>
 * （后台的"立即重算"接口）。逻辑写在任务里的话，接口层就得反过来依赖定时任务 ——
 * 那是把"什么时候跑"和"跑什么"耦合在了一起。
 *
 * @author MewChat
 */
@Service
public class QuestionClusteringService {

    private static final Logger log = LoggerFactory.getLogger(QuestionClusteringService.class);

    private final LowConfidenceQuestionService questionService;

    private final JobProperties jobProperties;

    public QuestionClusteringService(LowConfidenceQuestionService questionService,
                                     JobProperties jobProperties) {
        this.questionService = questionService;
        this.jobProperties = jobProperties;
    }

    /**
     * 对问题池做一次聚类，把结果写回池子。
     *
     * <p><b>每天全量重算，而不是增量维护</b>：簇的划分会随池子变化而变
     * （昨天孤立的两个问题今天可能被一条新问题连起来），增量维护要处理合并与拆分，
     * 复杂度远高于收益。
     *
     * <p><b>单个簇写失败不影响其它簇</b>，下一轮全量重算会自然修正。
     *
     * @return 本次聚类的统计结果
     */
    public ClusterOutcome clusterPendingQuestions() {
        JobProperties.Clustering config = jobProperties.getClustering();
        List<LowConfidenceQuestion> pending =
                questionService.listPendingForClustering(config.getBatchSize());

        if (pending.isEmpty()) {
            log.debug("问题池中没有待优化的问题，跳过聚类");
            return new ClusterOutcome(0, 0, 0, 0);
        }

        List<String> questions = pending.stream()
                .map(LowConfidenceQuestion::getQuestion)
                .toList();
        List<QuestionClusterer.Cluster> clusters =
                QuestionClusterer.cluster(questions, config.getSimilarityThreshold());

        int updatedRows = 0;
        int multiMemberClusters = 0;
        QuestionClusterer.Cluster largest = null;

        for (QuestionClusterer.Cluster cluster : clusters) {
            List<Long> ids = cluster.memberIndexes().stream()
                    .map(index -> pending.get(index).getId())
                    .toList();
            // 簇键取代表元的问题哈希：它本身有唯一键，既能当簇标识，
            // 又能直接追溯"这簇是以哪条问题命名的"
            String clusterKey = pending.get(cluster.representativeIndex()).getQuestionHash();

            try {
                updatedRows += questionService.assignCluster(ids, clusterKey, cluster.size());
            } catch (Exception e) {
                log.error("簇写入失败，跳过：clusterKey={} 成员数={}", clusterKey, cluster.size(), e);
            }

            if (cluster.size() > 1) {
                multiMemberClusters++;
            }
            if (largest == null || cluster.size() > largest.size()) {
                largest = cluster;
            }
        }

        log.info("问题池聚类完成：待优化 {} 条 → {} 个簇（多成员簇 {} 个），更新 {} 行；最大簇 {} 条：{}",
                pending.size(), clusters.size(), multiMemberClusters, updatedRows,
                largest == null ? 0 : largest.size(),
                largest == null ? "-" : shorten(questions.get(largest.representativeIndex())));

        return new ClusterOutcome(pending.size(), clusters.size(), multiMemberClusters, updatedRows);
    }

    /**
     * 截断过长的日志文本（问题原文上限 2000 字，整条打进日志会把任务日志刷得没法看）。
     *
     * @param text 原文
     * @return 至多 40 字的文本
     */
    private static String shorten(String text) {
        if (text == null) {
            return "-";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /**
     * 一次聚类的结果摘要。
     *
     * @param pendingQuestions   本次参与聚类的问题条数
     * @param clusterCount       得到的簇数量
     * @param multiMemberClusters 其中成员数大于 1 的簇数量（真正被"归并"掉的那些）
     * @param updatedRows        实际写回的行数
     */
    public record ClusterOutcome(int pendingQuestions,
                                 int clusterCount,
                                 int multiMemberClusters,
                                 int updatedRows) {
    }
}
