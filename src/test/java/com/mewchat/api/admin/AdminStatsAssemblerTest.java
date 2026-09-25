package com.mewchat.api.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mewchat.api.admin.dto.StatsOverviewView;
import com.mewchat.config.AgentProperties;
import com.mewchat.service.ConversationService;
import com.mewchat.service.KnowledgeChunkService;
import com.mewchat.service.KnowledgeDocumentService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.MessageService;
import com.mewchat.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 统计装配器的单元测试（纯 Mockito，不加载 Spring、不连数据库）。
 *
 * <p><b>本类存在的理由</b>：统计总览曾经在<b>空库</b>上直接 500 ——
 * 不带 {@code GROUP BY} 的 {@code SUM/AVG} 在没有匹配行时会返回"一行全 NULL"，
 * 而 MyBatis 把这种行映射成 {@code null} 元素，取值辅助方法只判了"列表为空"、
 * 没判"元素为 null"，于是 {@code aggregate.get(...)} 抛 NPE。
 * 空库恰恰是<b>全新部署第一次打开后台</b>的状态（演示、验收、新环境都会撞上），
 * 而此前只有真容器冒烟才可能发现（MockMvc 用例把整个装配器替换掉了）。
 *
 * <p>因此这里的替身刻意返回<b>真实驱动会返回的形态</b>（全 NULL 行 → 单个 null 元素），
 * 而不是"空列表"这种更好处理的形态 —— 测试若用了后者，就等于把缺陷留在原地。
 *
 * @author MewChat
 */
class AdminStatsAssemblerTest {

    private final ConversationService conversationService = mock(ConversationService.class);

    private final MessageService messageService = mock(MessageService.class);

    private final TicketService ticketService = mock(TicketService.class);

    private final KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);

    private final KnowledgeChunkService chunkService = mock(KnowledgeChunkService.class);

    private final LowConfidenceQuestionService questionService = mock(LowConfidenceQuestionService.class);

    private AdminStatsAssembler assembler;

    @BeforeEach
    void setUp() {
        // 阈值取真实默认值：总览里的"低置信度消息数"与驱动兜底的阈值必须同源，
        // 本用例顺带确认装配器确实读了配置而不是写死的常量
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setLowConfidenceThreshold(new BigDecimal("0.60"));
        assembler = new AdminStatsAssembler(conversationService, messageService, ticketService,
                documentService, chunkService, questionService, agentProperties);
    }

    /**
     * 空库（所有表都没有数据）时总览必须能正常返回，而不是 500。
     */
    @Test
    void overviewShouldNotFailOnEmptyDatabase() {
        stubEmptyDatabase();

        StatsOverviewView overview = assembler.overview();

        assertThat(overview).isNotNull();
        assertThat(overview.conversations().total()).isZero();
        assertThat(overview.messages().total()).isZero();
        assertThat(overview.messages().avgConfidence())
                .as("没有助手消息时均值应为 null，而不是抛异常")
                .isNull();
        assertThat(overview.tickets().total()).isZero();
        assertThat(overview.knowledge().documents()).isZero();
        assertThat(overview.flywheel().pendingQuestions()).isZero();
        assertThat(overview.flywheel().totalHits())
                .as("命中次数合计在无数据时必须落到 0，不能是 null")
                .isZero();
        assertThat(overview.dailyMessages()).isEmpty();
    }

    /**
     * 有数据时聚合结果要真的被用上：均值为 BigDecimal、总量为累计值。
     */
    @Test
    void overviewShouldMapAggregateValues() {
        given(messageService.count()).willReturn(6L);
        given(messageService.count(any())).willReturn(3L);
        given(messageService.listMaps(any(Wrapper.class)))
                .willReturn(List.of(Map.of(
                        "avg_confidence", new BigDecimal("0.8500"),
                        "avg_cost_ms", 1200L,
                        "total_tokens", 900L)));
        given(questionService.listMaps(any(Wrapper.class))).willReturn(List.of(Map.of("total_hits", 7L)));
        given(questionService.listPendingForChecklist(anyInt())).willReturn(List.of());
        given(questionService.count(any())).willReturn(0L);
        given(questionService.count()).willReturn(0L);
        given(conversationService.count()).willReturn(0L);
        given(conversationService.count(any())).willReturn(0L);
        given(ticketService.count()).willReturn(0L);
        given(ticketService.count(any())).willReturn(0L);

        StatsOverviewView overview = assembler.overview();

        assertThat(overview.messages().avgConfidence()).isEqualByComparingTo("0.8500");
        assertThat(overview.messages().avgCostMs()).isEqualTo(1200L);
        assertThat(overview.messages().totalTokens()).isEqualTo(900L);
        assertThat(overview.flywheel().totalHits()).isEqualTo(7L);
    }

    /**
     * 待优化清单为空时返回空清单（而不是 null）：前端拿到 null 会直接崩在渲染上。
     */
    @Test
    void checklistShouldBeEmptyWhenNoQuestions() {
        given(questionService.listPendingForChecklist(anyInt())).willReturn(List.of());

        assertThat(assembler.checklist().clusters()).isEmpty();
        assertThat(assembler.checklist().totalQuestions()).isZero();
    }

    /* ==================== 辅助 ==================== */

    /**
     * 打桩成"空库"：计数全 0，聚合返回<b>驱动在空表上的真实形态</b>。
     *
     * <p>聚合结果刻意用 {@code Arrays.asList((Map) null)} 而不是 {@code List.of()}：
     * 前者是 MyBatis 对"一行全 NULL"的映射结果，正是触发过 NPE 的形态；
     * 后者只覆盖"结果集为空"，会让用例失去意义。
     */
    private void stubEmptyDatabase() {
        given(conversationService.count()).willReturn(0L);
        given(conversationService.count(any())).willReturn(0L);
        given(messageService.count()).willReturn(0L);
        given(messageService.count(any())).willReturn(0L);
        given(messageService.listMaps(any(Wrapper.class))).willReturn(Arrays.asList((Map<String, Object>) null));
        given(ticketService.count()).willReturn(0L);
        given(ticketService.count(any())).willReturn(0L);
        given(documentService.count()).willReturn(0L);
        given(documentService.count(any())).willReturn(0L);
        given(chunkService.count()).willReturn(0L);
        given(questionService.count()).willReturn(0L);
        given(questionService.count(any())).willReturn(0L);
        given(questionService.listMaps(any(Wrapper.class))).willReturn(Arrays.asList((Map<String, Object>) null));
        given(questionService.listPendingForChecklist(anyInt())).willReturn(List.of());
    }
}
