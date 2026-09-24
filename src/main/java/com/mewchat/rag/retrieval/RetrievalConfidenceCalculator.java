package com.mewchat.rag.retrieval;

import com.mewchat.config.RagProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 检索置信度计算器。
 *
 * <p><b>为什么不直接用最高分当置信度</b>：只看最高分会把两种情况误判成一样可靠 ——
 * "只有一条 0.8 分的片段"和"有三条 0.8 分、互相印证的片段"。
 * 后者显然更可信：多个独立片段都指向同一结论，说明知识库里确实有明确依据，
 * 而不是碰巧撞上一条语义相近的无关内容。因此把<b>命中数量</b>也纳入计算。
 *
 * <p>公式：{@code confidence = w_top · 最高分 + w_count · min(1, 有效片段数 / 期望片段数)}
 *
 * <p>有效片段数只统计最终分不低于 {@code relevanceFloor} 的片段：
 * 一堆低分片段不该把置信度抬上去，那正是这个指标要防的事。
 *
 * @author MewChat
 */
@Component
public class RetrievalConfidenceCalculator {

    private final RagProperties ragProperties;

    public RetrievalConfidenceCalculator(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 计算置信度。
     *
     * @param chunks 重排后的片段列表
     * @return 0~1 的置信度，保留 4 位小数；无有效片段时为 0
     */
    public BigDecimal calculate(List<RetrievedChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return BigDecimal.ZERO;
        }
        RagProperties.Confidence config = ragProperties.getConfidence();

        double topScore = 0.0;
        int effectiveCount = 0;
        for (RetrievedChunk chunk : chunks) {
            double score = chunk.getScore() == null ? 0.0 : chunk.getScore();
            if (score >= config.getRelevanceFloor()) {
                effectiveCount++;
            }
            if (score > topScore) {
                topScore = score;
            }
        }

        int expected = Math.max(1, config.getExpectedChunkCount());
        double countFactor = Math.min(1.0, (double) effectiveCount / expected);

        double confidence = config.getTopScoreWeight() * topScore
                + config.getCountWeight() * countFactor;

        // 裁剪到 0~1：权重由配置决定，用户改配置有可能让加权和越界
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        return BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
    }
}
