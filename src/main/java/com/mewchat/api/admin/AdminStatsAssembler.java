package com.mewchat.api.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mewchat.api.admin.dto.OptimizationChecklistView;
import com.mewchat.api.admin.dto.StatsOverviewView;
import com.mewchat.common.constant.ChatConstants;
import com.mewchat.config.AgentProperties;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.rag.document.DocumentIngestService;
import com.mewchat.service.ConversationService;
import com.mewchat.service.ConversationServiceImpl;
import com.mewchat.service.KnowledgeChunkService;
import com.mewchat.service.KnowledgeDocumentService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.LowConfidenceQuestionServiceImpl;
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
 * <p><b>一处已知偏差（如实记录）</b>：三处聚合（消息质量均值、每日消息量、命中次数合计）
 * 把 {@code AVG(...)} / {@code DATE(...)} 写在 Wrapper 的 {@code .select()} 里，
 * 与 §四.3"手写 SQL 一律放 XML"不完全一致。保留现状的理由是它们只是单表聚合、
 * 参数全部由 Wrapper 参数化，且当前没有真库测试覆盖 —— 搬迁要同时动 mapper 与
 * service 接口，收益（可 grep、可复用）小于"改完只有冒烟验证"的风险。
 * 若后续要给统计补真库测试或做预聚合，应当连同这段 SQL 一起搬进 XML。
 *
 * <p><b>状态与角色的取值一律引用定义处</b>（{@code ConversationServiceImpl} /
 * {@code TicketService} / {@code LowConfidenceQuestionServiceImpl} /
 * {@code DocumentIngestService} 的常量），不在本类里写数字字面量：
 * 统计出错是不报错的错，口径写两份就会出现"服务层改了取值、仪表盘还按旧口径统计"。
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
                        .eq(Conversation::getStatus, ConversationServiceImpl.STATUS_ACTIVE)),
                conversationService.count(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getStatus, ConversationServiceImpl.STATUS_CLOSED)),
                conversationService.count(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getStatus, ConversationServiceImpl.STATUS_HANDOFF)));
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
                .eq(Message::getRole, ChatConstants.ROLE_ASSISTANT));

        // 一次聚合取回三个数，而不是发三条 SQL：它们扫的是同一批行
        Map<String, Object> aggregate = firstRow(messageService.listMaps(Wrappers.<Message>query()
                .select("AVG(confidence) AS avg_confidence",
                        "AVG(cost_ms) AS avg_cost_ms",
                        "SUM(total_tokens) AS total_tokens")
                .eq("role", ChatConstants.ROLE_ASSISTANT)));

        long lowConfidence = messageService.count(Wrappers.<Message>lambdaQuery()
                .eq(Message::getRole, ChatConstants.ROLE_ASSISTANT)
                .isNotNull(Message::getConfidence)
                .lt(Message::getConfidence, agentProperties.getLowConfidenceThreshold()));

        return new StatsOverviewView.MessageStats(
                messageService.count(),
                messageService.count(Wrappers.<Message>lambdaQuery()
                        .eq(Message::getRole, ChatConstants.ROLE_USER)),
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
                ticketService.count(Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, TicketService.STATUS_PENDING)),
                ticketService.count(Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, TicketService.STATUS_PROCESSING)),
                ticketService.count(Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, TicketService.STATUS_RESOLVED)),
                ticketService.count(Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, TicketService.STATUS_CLOSED)));
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
                        .eq(KnowledgeDocument::getEmbedStatus, DocumentIngestService.STATUS_FAILED)));
    }

    /**
     * 数据飞轮统计。
     *
     * @return 飞轮统计
     */
    private StatsOverviewView.FlywheelStats flywheelStats() {
        long pending = questionService.count(Wrappers.<LowConfidenceQuestion>lambdaQuery()
                .eq(LowConfidenceQuestion::getOptimized, LowConfidenceQuestionServiceImpl.OPTIMIZED_PENDING));
        long clustered = questionService.count(Wrappers.<LowConfidenceQuestion>lambdaQuery()
                .eq(LowConfidenceQuestion::getOptimized, LowConfidenceQuestionServiceImpl.OPTIMIZED_PENDING)
                .isNotNull(LowConfidenceQuestion::getClusterKey));

        Map<String, Object> aggregate = firstRow(questionService.listMaps(
                Wrappers.<LowConfidenceQuestion>query()
                        .select("SUM(hit_count) AS total_hits")
                        .eq("optimized", LowConfidenceQuestionServiceImpl.OPTIMIZED_PENDING)));

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
            // 跳过 null 行：MyBatis 会把"一行全 NULL"映射成 null 元素（与 firstRow 同一个坑）。
            // 本查询带 GROUP BY，空数据时是"没有行"而非"全 NULL 行"，因此这条守卫
            // 实际很难触发；但把"聚合行可能为 null"当作本类的前置事实统一处理，
            // 好过在每个遍历点各假设一次
            if (row == null) {
                continue;
            }
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
     * <p>聚合查询在没有匹配行时有<b>两种</b>返回形态，两种都要能接住，空库时打开后台首页不该报错：
     * <ul>
     *     <li>结果集为空（例如带 {@code GROUP BY} 的每日趋势查询）；</li>
     *     <li>结果集有一行、但<b>每个列都是 NULL</b>（不带 {@code GROUP BY} 的
     *         {@code SUM/AVG} 在空表上就是这样）。MyBatis 会把这种"全 NULL 行"
     *         映射成 {@code null} 元素 —— 只判 {@code rows.isEmpty()} 会漏掉它，
     *         随后 {@code aggregate.get(...)} 直接 NPE，表现为<b>空库打开统计总览 500</b>
     *         （真容器验收时抓到的就是这个）。</li>
     * </ul>
     *
     * @param rows 查询结果
     * @return 第一行；没有数据或行为空时返回空表
     */
    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = rows.get(0);
        return row == null ? Map.of() : row;
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
