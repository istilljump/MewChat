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
     * 只有一条高分片段时，数量因子只拿三分之一。
     *
     * <p>这正是引入数量因子的目的：单条依据的可信度天然低于多条互相印证。
     * 期望值 = 0.7 × 1.0 + 0.3 × (1/3) = 0.8
     */
    @Test
    void singleHighScoreChunkShouldNotReachFullConfidence() {
        assertThat(calculator.calculate(List.of(chunk(1.0)))).isEqualByComparingTo("0.8");
    }

    /**
     * 三条高分片段达到满置信度。
     *
     * <p>0.7 × 1.0 + 0.3 × (3/3) = 1.0
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
