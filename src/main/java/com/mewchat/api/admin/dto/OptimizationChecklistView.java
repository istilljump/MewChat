package com.mewchat.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 待优化清单（数据飞轮的产出）。
 *
 * <p>问题池每天被聚一次类，这里按<b>簇规模</b>从大到小给出：
 * 规模就是"多少人被这个问题卡住"，它比"哪条问题命中次数高"更接近运营的优先级。
 *
 * @param totalClusters  本次返回的簇数量
 * @param totalQuestions 本次返回的问题条数（各簇规模之和）
 * @param clusters       簇列表，按规模降序
 * @author MewChat
 */
public record OptimizationChecklistView(

        int totalClusters,

        long totalQuestions,

        List<ClusterItem> clusters) {

    /**
     * 一个簇。
     *
     * @param clusterKey             簇键（代表元的问题哈希），同一次聚类内唯一
     * @param representativeQuestion 代表问题：该簇里被问得最多的那句问法
     * @param size                   簇规模（问题条数，按归一化问法去重后）
     * @param totalHits              簇内所有问题的累计出现次数，反映真实影响面
     * @param worstConfidence        簇内最差的一次置信度
     * @param lastSeenTime           簇内最近一次出现的时间
     * @param questions              簇内的问题明细
     */
    public record ClusterItem(

            String clusterKey,

            String representativeQuestion,

            int size,

            long totalHits,

            BigDecimal worstConfidence,

            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
            LocalDateTime lastSeenTime,

            List<QuestionItem> questions) {
    }

    /**
     * 簇内的一条问题。
     *
     * @param id         问题ID
     * @param question   问题原文
     * @param hitCount   该问法累计出现次数
     * @param confidence 该问法最差的一次置信度
     * @param lastSeen   最近一次出现的时间
     */
    public record QuestionItem(

            Long id,

            String question,

            Integer hitCount,

            BigDecimal confidence,

            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
            LocalDateTime lastSeen) {
    }
}
