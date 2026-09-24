package com.mewchat.api.admin.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据统计总览（后台首页）。
 *
 * <p>三个问题一次答完：<b>系统被用得怎么样</b>（会话/消息）、
 * <b>答得好不好</b>（平均置信度、兜底量）、<b>哪里在漏水</b>
 * （待处理工单、入库失败的文档、待优化问题）。
 *
 * <p><b>为什么合成一个接口而不是拆成四个</b>：后台首页要同时展示这些数字，
 * 拆开就是四次往返；而这些查询都是"扫一遍表做个聚合"，
 * 合并成一个接口反而是更少的数据库压力。
 * 需要单独刷新某一块时，由前端自己决定重拉整份还是不管。
 *
 * @param conversations          会话统计
 * @param messages               消息与质量统计
 * @param tickets                工单统计
 * @param knowledge              知识库统计
 * @param flywheel               数据飞轮统计
 * @param topUnresolvedQuestions 待优化问题 Top N
 * @param dailyMessages          近 7 天每日消息量，按日期升序
 * @author MewChat
 */
public record StatsOverviewView(

        ConversationStats conversations,

        MessageStats messages,

        TicketStats tickets,

        KnowledgeStats knowledge,

        FlywheelStats flywheel,

        List<TopQuestion> topUnresolvedQuestions,

        List<DailyCount> dailyMessages) {

    /**
     * 会话统计。
     *
     * @param total    会话总数
     * @param active   进行中
     * @param closed   已结束
     * @param handoff  已转人工
     */
    public record ConversationStats(long total, long active, long closed, long handoff) {
    }

    /**
     * 消息与回答质量统计。
     *
     * <p>{@code avgConfidence} 只统计助手消息：用户消息没有置信度这个概念，
     * 把它算进去会让平均值被一堆 0 稀释成一个看着很糟、其实毫无意义的数字。
     *
     * @param total              消息总数
     * @param userMessages       用户消息数
     * @param assistantMessages  助手消息数
     * @param avgConfidence      助手消息的平均置信度
     * @param lowConfidenceCount 置信度低于兜底线（0.4）的助手消息数
     * @param avgCostMs          助手消息平均耗时（毫秒）
     * @param totalTokens        累计 token 消耗
     */
    public record MessageStats(long total,
                              long userMessages,
                              long assistantMessages,
                              BigDecimal avgConfidence,
                              long lowConfidenceCount,
                              Long avgCostMs,
                              Long totalTokens) {
    }

    /**
     * 工单统计。
     *
     * @param total      工单总数
     * @param pending    待处理
     * @param processing 处理中
     * @param resolved   已解决
     * @param closed     已关闭
     */
    public record TicketStats(long total, long pending, long processing, long resolved, long closed) {
    }

    /**
     * 知识库统计。
     *
     * @param documents      文档总数
     * @param chunks         切片总数
     * @param failedDocuments 向量化失败的文档数（>0 说明有文档检索不到）
     */
    public record KnowledgeStats(long documents, long chunks, long failedDocuments) {
    }

    /**
     * 数据飞轮统计。
     *
     * @param pendingQuestions   待优化的问题条数
     * @param clusteredQuestions 其中已经完成聚类的条数；为 0 说明聚类任务还没跑过
     * @param totalHits          待优化问题的累计命中次数（真实影响面）
     */
    public record FlywheelStats(long pendingQuestions, long clusteredQuestions, long totalHits) {
    }

    /**
     * 待优化问题（Top N）。
     *
     * @param question   问题原文
     * @param hitCount   累计出现次数
     * @param confidence 最差的一次置信度
     * @param clusterSize 所在簇的规模；0 表示尚未聚类
     */
    public record TopQuestion(String question, Integer hitCount, BigDecimal confidence, Integer clusterSize) {
    }

    /**
     * 每日消息量。
     *
     * @param date  日期，格式 yyyy-MM-dd
     * @param count 当天消息数
     */
    public record DailyCount(String date, long count) {
    }
}
