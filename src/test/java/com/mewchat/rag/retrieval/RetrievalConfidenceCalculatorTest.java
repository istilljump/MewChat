package com.mewchat.rag.retrieval;

import com.mewchat.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 置信度计算测试（纯单元测试）。
 *
 * <p>默认权重为 最高分 0.7 / 命中数量 0.3，期望有效片段数 3，相关性下限 0.5。
 *
 * @author MewChat
 */
class RetrievalConfidenceCalculatorTest {

    private final RagProperties properties = new RagProperties();

    private final RetrievalConfidenceCalculator calculator = new RetrievalConfidenceCalculator(properties);

    /**
     * 无片段时置信度为 0。
     */
    @Test
    void emptyInputShouldYieldZero() {
        assertThat(calculator.calculate(List.of())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.calculate(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 单条片段也能拿满分，但只有它自身就是满分时才如此（重排分 1.0 = 词项全覆盖 + 标题命中）。
     *
     * <p><b>阶段 12 改了这里的口径，说明写在这里以免日后被误当成回退</b>：
     * 原先 {@code expected-chunk-count=3} 把单条依据压成 {@code 0.7×分 + 0.1}，
     * 于是"单片命中"永远够不到达标线 —— 端到端实测里检索精准命中了正确片段
     * （引用正确、重排分 0.714）却算出 0.60、白跑一次补检索后走兜底。
     * 新知识库往往只召回一两片，那等于"知识刚录进去也答不上来"。
     * 现在数量因子退化为"至少有一条达标片段"这道门槛，佐证关系由最高分体现。
     *
     * <p>防线并没有因此丢掉：<b>排第一但毫无词面重叠</b>的片段重排分恰好是融合项下限 0.5，
     * 置信度上界 {@code 0.7×0.5 + 0.3 = 0.65} 仍低于达标线 0.70
     * （见 {@code RagConfidenceCeilingTest}）。"排第一"本身依然不足以作答。
     */
    @Test
    void singleChunkShouldReachFullConfidenceOnlyWithPerfectScore() {
        assertThat(calculator.calculate(List.of(chunk(1.0)))).isEqualByComparingTo("1.0");
        // 0.7 × 0.6 + 0.3 × 1 = 0.72：够作答，但明显低于满分的 1.0
        assertThat(calculator.calculate(List.of(chunk(0.6)))).isEqualByComparingTo("0.7200");
    }

    /**
     * 多条高分片段同样达到满置信度。
     *
     * <p>0.7 × 1.0 + 0.3 × min(1, 3/1) = 1.0
     */
    @Test
    void enoughHighScoreChunksShouldReachFullConfidence() {
        assertThat(calculator.calculate(List.of(chunk(1.0), chunk(0.9), chunk(0.8))))
                .isEqualByComparingTo("1.0");
    }

    /**
     * 低于相关性下限的片段不计入有效数量。
     *
     * <p>这条断言防的是"靠一堆无关片段把置信度堆上去" —— 那正是这个指标要防的事。
     * 期望值 = 0.7 × 0.45（最高分，虽低于下限但仍参与最高分计算）
     *        + 0.3 × 0（两条都在下限 0.5 以下，数量因子为 0）= 0.315
     */
    @Test
    void chunksBelowRelevanceFloorShouldNotCount() {
        assertThat(calculator.calculate(List.of(chunk(0.4), chunk(0.45)))).isEqualByComparingTo("0.315");
    }

    /**
     * 权重配置越界时应被裁剪到 0~1，而不是把越界值透出去。
     */
    @Test
    void outOfRangeWeightsShouldBeClamped() {
        RagProperties.Confidence confidence = properties.getConfidence();
        confidence.setTopScoreWeight(0.9);
        confidence.setCountWeight(0.9);

        assertThat(calculator.calculate(List.of(chunk(1.0), chunk(1.0), chunk(1.0))))
                .isEqualByComparingTo("1.0");
    }

    /**
     * 构造一条指定最终分的片段。
     *
     * @param score 最终分
     * @return 片段
     */
    private RetrievedChunk chunk(double score) {
        return RetrievedChunk.builder()
                .chunkId("1_1")
                .docId(1L)
                .chunkNo(1)
                .text("测试")
                .score(score)
                .sources("bm25")
                .build();
    }
}
