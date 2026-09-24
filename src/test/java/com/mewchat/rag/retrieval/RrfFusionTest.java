package com.mewchat.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合测试（纯单元测试）。
 *
 * @author MewChat
 */
class RrfFusionTest {

    private static final int K = 60;

    private final RrfFusion fusion = new RrfFusion();

    /**
     * 两路皆空时返回空列表。
     */
    @Test
    void bothEmptyShouldReturnEmpty() {
        assertThat(fusion.fuse(List.of(), List.of(), K)).isEmpty();
        assertThat(fusion.fuse(null, null, K)).isEmpty();
    }

    /**
     * 只有一路有结果时也应正常融合，且首名归一化为 1.0。
     */
    @Test
    void singleChannelShouldStillNormalize() {
        List<RetrievedChunk> vector = List.of(chunk("a", 0.9), chunk("b", 0.8));

        List<RetrievedChunk> fused = fusion.fuse(vector, List.of(), K);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).getChunkId()).isEqualTo("a");
        assertThat(fused.get(0).getScore()).isEqualTo(1.0);
        // 第 2 名的相对分应为 (1/62) / (1/61) = 61/62
        assertThat(fused.get(1).getScore()).isCloseTo(61.0 / 62.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * 同一片段被两路同时命中时应合并为一条，而不是出现两次。
     *
     * <p>这正是 RRF 的价值之一：两路都召回的片段获得更高名次，
     * 但如果不去重，用户会看到重复的引用来源。
     */
    @Test
    void sameChunkFromBothChannelsShouldBeMerged() {
        List<RetrievedChunk> vector = List.of(chunk("a", 0.9));
        List<RetrievedChunk> bm25 = List.of(chunk("a", 3.5, "bm25"));

        List<RetrievedChunk> fused = fusion.fuse(vector, bm25, K);

        assertThat(fused).hasSize(1);
        RetrievedChunk merged = fused.get(0);
        assertThat(merged.getSources()).isEqualTo("vector+bm25");
        // 两路的原始分都要保留下来，供排查"这段为什么排前面"
        assertThat(merged.getVectorScore()).isEqualTo(0.9);
        assertThat(merged.getBm25Score()).isEqualTo(3.5);
    }

    /**
     * 被两路同时命中（哪怕名次靠后）应优于只被一路命中的第一名。
     *
     * <p>这是 RRF 相较"取最高分"的核心差异：多路一致认可比单路极端高分更可信。
     */
    @Test
    void agreedByBothChannelsShouldOutrankSingleChannelTop() {
        // a 只在向量通道排第 1；b 在两路都排第 2
        List<RetrievedChunk> vector = List.of(chunk("a", 0.99), chunk("b", 0.5));
        List<RetrievedChunk> bm25 = List.of(chunk("c", 9.0, "bm25"), chunk("b", 2.0, "bm25"));

        List<RetrievedChunk> fused = fusion.fuse(vector, bm25, K);

        assertThat(fused.get(0).getChunkId())
                .as("两路都命中的 b 应排在只单路命中的 a 之前")
                .isEqualTo("b");
    }

    /**
     * 融合结果应按分数降序排列。
     */
    @Test
    void resultShouldBeSortedByScoreDescending() {
        List<RetrievedChunk> vector = List.of(chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.7));

        List<RetrievedChunk> fused = fusion.fuse(vector, List.of(), K);

        assertThat(fused).extracting(RetrievedChunk::getScore).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
    }

    /**
     * 构造一条只属于向量通道的候选。
     *
     * @param chunkId 片段键
     * @param score   原始向量相似度
     * @return 候选片段
     */
    private RetrievedChunk chunk(String chunkId, double score) {
        return chunk(chunkId, score, "vector");
    }

    /**
     * 构造一条候选。
     *
     * <p>必须按来源设置对应的原始分字段：两个通道的分数量纲不同，
     * 若给关键词通道的结果也塞 {@code vectorScore}，融合时就会用错误的值
     * 覆盖掉向量通道的真实相似度。
     *
     * @param chunkId 片段键
     * @param score   原始分
     * @param source  来源：vector / bm25
     * @return 候选片段
     */
    private RetrievedChunk chunk(String chunkId, double score, String source) {
        RetrievedChunk.RetrievedChunkBuilder builder = RetrievedChunk.builder()
                .chunkId(chunkId)
                .docId(1L)
                .chunkNo(1)
                .text("测试文本 " + chunkId)
                .sources(source);
        if ("bm25".equals(source)) {
            builder.bm25Score(score);
        } else {
            builder.vectorScore(score);
        }
        return builder.build();
    }
}
