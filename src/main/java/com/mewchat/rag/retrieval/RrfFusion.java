package com.mewchat.rag.retrieval;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合器。
 *
 * <p><b>为什么需要融合</b>：向量通道与关键词通道各自会漏，也各自会误召。
 * 只用一路，另一路的正确结果就永远进不了最终答案。
 *
 * <p><b>为什么用 RRF 而不是把两路分数加权求和</b>：
 * 两路分数量纲完全不同 —— 向量相似度是 0~1 的余弦值，BM25 是无上界的对数加权和，
 * 而且 BM25 的绝对取值范围随语料规模变化。直接把它们相加需要先做归一化，
 * 而归一化本身依赖当次结果集、跨查询不稳定。
 * RRF 只使用<b>排名</b>不做分数运算，天然规避了这个问题，
 * 也因此被广泛用作多路召回的标准融合方式。
 *
 * <p>公式：{@code score(d) = Σ_r 1 / (k + rank_r(d))}，rank 从 1 开始。
 * {@code k} 越大，靠前排名的优势越平缓；常用取值 60。
 *
 * @author MewChat
 */
@Component
public class RrfFusion {

    /**
     * 融合两路召回结果。
     *
     * <p>同一片段被两路同时命中时只保留一条，但会把两路的原始分都记下来 ——
     * 这既是重排的输入，也是排查"这段为什么排前面"的依据。
     *
     * @param vectorResults 向量通道结果，按相关性降序
     * @param bm25Results   关键词通道结果，按相关性降序
     * @param k             RRF 平滑参数
     * @return 融合后的片段，按融合分降序；两路皆空时返回空列表
     */
    public List<RetrievedChunk> fuse(List<RetrievedChunk> vectorResults,
                                     List<RetrievedChunk> bm25Results,
                                     int k) {
        if (CollectionUtils.isEmpty(vectorResults) && CollectionUtils.isEmpty(bm25Results)) {
            return List.of();
        }

        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        Map<String, Double> fusedScores = new HashMap<>();

        accumulate(vectorResults, k, "vector", merged, fusedScores);
        accumulate(bm25Results, k, "bm25", merged, fusedScores);

        // 归一化到 0~1：RRF 原始分很小（k=60 时单路第一名只有约 0.016），
        // 不归一化会让后续重排与置信度计算都要依赖 k 的取值
        double maxScore = fusedScores.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1.0);
        if (maxScore <= 0) {
            maxScore = 1.0;
        }

        List<RetrievedChunk> fused = new ArrayList<>(merged.size());
        for (Map.Entry<String, RetrievedChunk> entry : merged.entrySet()) {
            RetrievedChunk chunk = entry.getValue();
            chunk.setScore(fusedScores.getOrDefault(entry.getKey(), 0.0) / maxScore);
            fused.add(chunk);
        }

        fused.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());
        return fused;
    }

    /**
     * 累加一路召回结果的 RRF 贡献。
     *
     * @param results     某一路的召回结果
     * @param k           RRF 平滑参数
     * @param source      来源标识（vector / bm25）
     * @param merged      已合并片段，按片段键索引
     * @param fusedScores 已累计的融合分
     */
    private void accumulate(List<RetrievedChunk> results,
                            int k,
                            String source,
                            Map<String, RetrievedChunk> merged,
                            Map<String, Double> fusedScores) {
        if (CollectionUtils.isEmpty(results)) {
            return;
        }
        int rank = 0;
        for (RetrievedChunk result : results) {
            rank++;
            String key = result.getChunkId();
            fusedScores.merge(key, 1.0 / (k + rank), Double::sum);
            merged.merge(key, result, (existing, incoming) -> merge(existing, incoming, source));
        }
    }

    /**
     * 合并同一片段在两条通道上的信息。
     *
     * <p>以先到者为基础，把另一路的原始分与来源补上。
     * 文本以非空者为准 —— 理论上两路文本一致，但关键词通道是从 MySQL 读的，
     * 万一向量库那份为空，这里能兜住。
     *
     * @param existing 已有片段
     * @param incoming 新到片段
     * @param source   新到片段来自哪一路
     * @return 合并后的片段
     */
    private RetrievedChunk merge(RetrievedChunk existing, RetrievedChunk incoming, String source) {
        if (!existing.getSources().contains(source)) {
            existing.setSources(existing.getSources() + "+" + source);
        }
        if (incoming.getVectorScore() != null) {
            existing.setVectorScore(incoming.getVectorScore());
        }
        if (incoming.getBm25Score() != null) {
            existing.setBm25Score(incoming.getBm25Score());
        }
        if (existing.getText() == null || existing.getText().isEmpty()) {
            existing.setText(incoming.getText());
        }
        return existing;
    }
}
