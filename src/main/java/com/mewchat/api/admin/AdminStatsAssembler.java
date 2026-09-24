package com.mewchat.api.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mewchat.api.admin.dto.OptimizationChecklistView;
import com.mewchat.api.admin.dto.StatsOverviewView;
import com.mewchat.config.AgentProperties;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.service.ConversationService;
import com.mewchat.service.KnowledgeChunkService;
import com.mewchat.service.KnowledgeDocumentService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.MessageService;
import com.mewchat.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运营数据统计的装配器。
 *
 * <p><b>为什么它在本层（api）而不在 service 层</b>：统计的产出形状<b>就是接口契约本身</b>。
 * 若把它放回 service，就必须再定义一套 service 层的视图类型、再写一遍逐字段映射 ——
 * 那份镜像没有任何独立价值，只会制造"改了 DTO 忘了改镜像"的隐患。
 * 这个类<b>只读不写</b>、不改变任何业务状态，因此把它看作"查询 + 整形"的接口适配更贴切。
 *
 * <p>代价要说清楚：报表口径（比如"哪些消息算答得不好"）现在与接口定义放在一起。
 * 等报表复杂到需要多个页面复用、或需要下推到数仓时，
 * 应当整体迁到独立的读模型服务，而不是继续往这个类里加。
 *
 * <p><b>查询成本</b>：全部是单表计数与一次聚合，没有 join。
 * 数据量大了以后这些 COUNT 会成为慢查询，届时该做的是预聚合（定时任务算好落表），
 * 而不是让后台首页继续实时扫表。
 *
 * @author MewChat
 */
@Component
public class AdminStatsAssembler {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsAssembler.class);

    /** 总览里展示的待优化问题条数 */
    private static final int TOP_QUESTION_LIMIT = 10;

    /** 近 N 天的消息量趋势 */
    private static final int TREND_DAYS = 7;

    /** 待优化清单一次最多读取的问题行数（簇可能因此被截断，见 checklist 注释） */
    private static final int CHECKLIST_ROW_LIMIT = 200;

    /** 尚未聚类的问题所用的假簇键 */
    private static final String UNCLUSTERED_KEY = "";

    private final ConversationService conversationService;

    private final MessageService messageService;

    private final TicketService ticketService;

    private final KnowledgeDocumentService documentService;

    private final KnowledgeChunkService chunkService;

    private final LowConfidenceQuestionService questionService;

    /**
     * 编排配置：总览里的"低置信度消息数"必须与真正驱动兜底的阈值取自同一处。
     *
     * <p>此处原先写的是常量 {@code 0.40}，与 {@code mewchat.agent.low-confidence-threshold}
     * 各写一份。运营一旦调整那个阈值（比如为了更保守而调到 0.6），
     * 兜底行为变了、而仪表盘上"答得不好"的数字还在按 0.40 算 ——
     * 数字与真实行为不一致，恰恰在人最需要它准确（正在调阈值）的时候失去意义。
     */
    private final AgentProperties agentProperties;

    public AdminStatsAssembler(ConversationService conversationService,
                              MessageService messageService,
                              TicketService ticketService,
                              KnowledgeDocumentService documentService,
                              KnowledgeChunkService chunkService,
                              LowConfidenceQuestionService questionService,
                              AgentProperties agentProperties) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.ticketService = ticketService;
        this.documentService = documentService;
        this.chunkService = chunkService;
        this.questionService = questionService;
        this.agentProperties = agentProperties;
    }

    /* ==================== 总览 ==================== */

    /**
     * 汇总数据总览。
     *
     * @return 总览视图
     */
    public StatsOverviewView overview() {
        return new StatsOverviewView(
                conversationStats(),
                messageStats(),
                ticketStats(),
                knowledgeStats(),
                flywheelStats(),
                topQuestions(),
                dailyMessages());
    }

    /**
     * 会话统计。
     *
     * @return 会话统计
     */
    private StatsOverviewView.ConversationStats conversationStats() {
        return new StatsOverviewView.ConversationStats(
                conversationService.count(),
                conversationService.count(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getStatus, 1)),
                conversationService.count(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getStatus, 2)),
                conversationService.count(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getStatus, 3)));
    }

    /**
     * 消息与回答质量统计。
     *
     * <p>平均值只用助手消息：用户消息没有置信度，算进去会把它稀释成一个
     * 看着很糟、其实毫无意义的数字。
     *
     * @return 消息统计
     */
    private StatsOverviewView.MessageStats messageStats() {
        long assistantMessages = messageService.count(Wrappers.<Message>lambdaQuery()
                .eq(Message::getRole, "assistant"));

        // 一次聚合取回三个数，而不是发三条 SQL：它们扫的是同一批行
        Map<String, Object> aggregate = firstRow(messageService.listMaps(Wrappers.<Message>query()
                .select("AVG(confidence) AS avg_confidence",
                        "AVG(cost_ms) AS avg_cost_ms",
                        "SUM(total_tokens) AS total_tokens")
                .eq("role", "assistant")));

        long lowConfidence = messageService.count(Wrappers.<Message>lambdaQuery()
                .eq(Message::getRole, "assistant")
                .isNotNull(Message::getConfidence)
                .lt(Message::getConfidence, agentProperties.getLowConfidenceThreshold()));

        return new StatsOverviewView.MessageStats(
                messageService.count(),
                messageService.count(Wrappers.<Message>lambdaQuery().eq(Message::getRole, "user")),
                assistantMessages,
                toBigDecimal(aggregate.get("avg_confidence")),
                lowConfidence,
                toLong(aggregate.get("avg_cost_ms")),
                toLong(aggregate.get("total_tokens")));
    }

    /**
     * 工单统计。
     *
     * @return 工单统计
     */
    private StatsOverviewView.TicketStats ticketStats() {
        return new StatsOverviewView.TicketStats(
                ticketService.count(),
                ticketService.count(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getStatus, 0)),
                ticketService.count(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getStatus, 1)),
                ticketService.count(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getStatus, 2)),
                ticketService.count(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getStatus, 3)));
    }

    /**
     * 知识库统计。
     *
     * @return 知识库统计
     */
    private StatsOverviewView.KnowledgeStats knowledgeStats() {
        return new StatsOverviewView.KnowledgeStats(
                documentService.count(),
                chunkService.count(),
                // 入库失败的文档在检索里是"查不到"的，这个数字必须能被看见
                documentService.count(Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getEmbedStatus, 3)));
    }

    /**
     * 数据飞轮统计。
     *
     * @return 飞轮统计
     */
    private StatsOverviewView.FlywheelStats flywheelStats() {
        long pending = questionService.count(Wrappers.<LowConfidenceQuestion>lambdaQuery()
                .eq(LowConfidenceQuestion::getOptimized, 0));
        long clustered = questionService.count(Wrappers.<LowConfidenceQuestion>lambdaQuery()
                .eq(LowConfidenceQuestion::getOptimized, 0)
                .isNotNull(LowConfidenceQuestion::getClusterKey));

        Map<String, Object> aggregate = firstRow(questionService.listMaps(
                Wrappers.<LowConfidenceQuestion>query()
                        .select("SUM(hit_count) AS total_hits")
                        .eq("optimized", 0)));

        return new StatsOverviewView.FlywheelStats(
                pending, clustered, Objects.requireNonNullElse(toLong(aggregate.get("total_hits")), 0L));
    }

    /**
     * 待优化问题 Top N。
     *
     * @return 问题列表
     */
    private List<StatsOverviewView.TopQuestion> topQuestions() {
        return questionService.listPendingForChecklist(TOP_QUESTION_LIMIT).stream()
                .map(question -> new StatsOverviewView.TopQuestion(
                        question.getQuestion(),
                        question.getHitCount(),
                        question.getConfidence(),
                        question.getClusterSize()))
                .toList();
    }

    /**
     * 近 N 天每日消息量。
     *
     * <p>只返回<b>有数据的日子</b>：中间没有消息的那天不补零，
     * 补零需要知道时区与"今天算不算"，而这两件事由前端按自己的时区处理更合适。
     *
     * @return 每日消息量，按日期升序
     */
    private List<StatsOverviewView.DailyCount> dailyMessages() {
        LocalDate from = LocalDate.now().minusDays(TREND_DAYS - 1L);
        List<Map<String, Object>> rows = messageService.listMaps(Wrappers.<Message>query()
                .select("DATE(create_time) AS day", "COUNT(*) AS cnt")
                .ge("create_time", from.atStartOfDay())
                .groupBy("DATE(create_time)")
                .orderByAsc("DATE(create_time)"));

        List<StatsOverviewView.DailyCount> trend = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            trend.add(new StatsOverviewView.DailyCount(
                    String.valueOf(row.get("day")), Objects.requireNonNullElse(toLong(row.get("cnt")), 0L)));
        }
        return trend;
    }

    /* ==================== 待优化清单 ==================== */

    /**
     * 按簇装配待优化清单。
     *
     * <p>分组在内存里完成：问题池每天只被聚一次类，读取时行数已经被
     * {@code limit} 封顶，没有再做一次 GROUP BY 的必要。
     *
     * <p><b>两个诚实的妥协</b>：
     * <ul>
     *     <li>行数上限可能把一个小簇截断，因此簇规模取<b>库里记录的 cluster_size</b>
     *         而不是内存里的成员数 —— 后者会低估</li>
     *     <li>尚未聚类的问题（cluster_key 为空，通常是聚类任务还没跑过）各自单独成簇，
     *         而不是被塞进一个巨大的"未聚类"桶：那会给出一个
     *         "几百条问题聚在一起"的假象</li>
     * </ul>
     *
     * @return 待优化清单
     */
    public OptimizationChecklistView checklist() {
        List<LowConfidenceQuestion> rows = questionService.listPendingForChecklist(CHECKLIST_ROW_LIMIT);

        Map<String, List<LowConfidenceQuestion>> grouped = new LinkedHashMap<>();
        for (LowConfidenceQuestion row : rows) {
            String key = row.getClusterKey();
            if (key == null || key.isBlank()) {
                // 未聚类：自己一组，键用空串占位（前端按数组顺序展示即可）
                grouped.computeIfAbsent(UNCLUSTERED_KEY + row.getId(), ignored -> new ArrayList<>()).add(row);
            } else {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }

        List<OptimizationChecklistView.ClusterItem> clusters = new ArrayList<>(grouped.size());
        for (List<LowConfidenceQuestion> members : grouped.values()) {
            if (members.isEmpty()) {
                continue;
            }
            // 行已按簇规模、命中次数降序，所以组内第一条就是代表元（命中次数最高的那条问法）
            LowConfidenceQuestion representative = members.get(0);
            long totalHits = members.stream()
                    .map(LowConfidenceQuestion::getHitCount)
                    .filter(Objects::nonNull)
                    .mapToLong(Integer::longValue)
                    .sum();
            BigDecimal worstConfidence = members.stream()
                    .map(LowConfidenceQuestion::getConfidence)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(null);
            LocalDateTime lastSeen = members.stream()
                    .map(LowConfidenceQuestion::getUpdateTime)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);

            clusters.add(new OptimizationChecklistView.ClusterItem(
                    representative.getClusterKey() == null ? UNCLUSTERED_KEY : representative.getClusterKey(),
                    representative.getQuestion(),
                    clusterSizeOf(representative, members.size()),
                    totalHits,
                    worstConfidence,
                    lastSeen,
                    members.stream()
                            .map(member -> new OptimizationChecklistView.QuestionItem(
                                    member.getId(),
                                    member.getQuestion(),
                                    member.getHitCount(),
                                    member.getConfidence(),
                                    member.getUpdateTime()))
                            .toList()));
        }

        long totalQuestions = clusters.stream()
                .mapToLong(OptimizationChecklistView.ClusterItem::size)
                .sum();
        return new OptimizationChecklistView(clusters.size(), totalQuestions, clusters);
    }

    /**
     * 取簇规模：优先用库里记录的值，缺失时退回内存成员数。
     *
     * @param representative 代表元
     * @param memberCount    内存中的成员数
     * @return 簇规模
     */
    private static int clusterSizeOf(LowConfidenceQuestion representative, int memberCount) {
        Integer recorded = representative.getClusterSize();
        return recorded == null || recorded <= 0 ? memberCount : recorded;
    }

    /* ==================== 取值辅助 ==================== */

    /**
     * 取聚合结果的第一行。
     *
     * <p>聚合查询在没有匹配行时会返回一行全 null（或干脆没有行），两种都要能接住 ——
     * 空库时打开后台首页不该报错。
     *
     * @param rows 查询结果
     * @return 第一行；没有数据时返回空表
     */
    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /**
     * 把聚合结果转成 Long。
     *
     * <p>不同数据库、不同聚合函数返回的类型不一样（COUNT 给 Long、SUM 可能给
     * BigDecimal），因此按数值统一转换，而不是假设某个具体类型。
     *
     * @param value 原始值
     * @return 数值；null 或无法解析时返回 null
     */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return new BigDecimal(value.toString()).longValue();
        } catch (NumberFormatException e) {
            log.warn("聚合结果不是数字，已忽略：{}", value);
            return null;
        }
    }

    /**
     * 把聚合结果转成 BigDecimal。
     *
     * @param value 原始值
     * @return 数值；null 或无法解析时返回 null
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("聚合结果不是数字，已忽略：{}", value);
            return null;
        }
    }
}
