package com.mewchat.rag.rerank;

import com.mewchat.rag.retrieval.ChunkTokenizer;
import com.mewchat.rag.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Comparator;
import java.util.List;

/**
 * 规则式重排器。
 *
 * <p>在融合分的基础上叠加两个"内容层面"的信号，把真正切题的片段顶上来：
 * <ul>
 *     <li><b>词项覆盖度</b> —— 查询切出的词项有多少出现在片段里。
 *         这是对 RRF 最有效的补充：RRF 只懂排名，不懂内容，
 *         同一片段若被两路分别排在第 5 名，照样可能排在一堆"只被一路排第 1" 的前面</li>
 *     <li><b>标题匹配</b> —— 查询词出现在文档标题里时给加成。
 *         标题是文档主题的浓缩，命中标题通常意味着整篇文档都在讲这件事</li>
 * </ul>
 *
 * <p>三项加权后重新归一化到 0~1，供置信度计算与前端展示使用。
 *
 * @author MewChat
 */
@Component
public class HeuristicReranker implements Reranker {

    /** 融合分权重 */
    private static final double WEIGHT_FUSION = 0.5;

    /** 词项覆盖度权重 */
    private static final double WEIGHT_COVERAGE = 0.4;

    /** 标题匹配加成 */
    private static final double WEIGHT_TITLE = 0.1;

    private final ChunkTokenizer tokenizer;

    public HeuristicReranker(ChunkTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topN) {
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }
        // 查询切不出词项（例如只有一个汉字）时，覆盖度与标题信号都失效，
        // 此时保持融合分原样，避免把分数全部打成 0 导致置信度归零
        List<String> terms = tokenizer.tokenize(query, Integer.MAX_VALUE);

        for (RetrievedChunk chunk : chunks) {
            double fusionScore = chunk.getScore() == null ? 0.0 : chunk.getScore();
            double coverage = terms.isEmpty() ? fusionScore : coverage(terms, chunk.getText());
            double titleHit = terms.isEmpty() ? 0.0 : titleHit(terms, chunk.getDocTitle());

            double finalScore = WEIGHT_FUSION * fusionScore
                    + WEIGHT_COVERAGE * coverage
                    + WEIGHT_TITLE * titleHit;
            chunk.setScore(clamp(finalScore));
        }

        chunks.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());
        return chunks.size() > topN ? List.copyOf(chunks.subList(0, topN)) : List.copyOf(chunks);
    }

    /**
     * 计算查询词项在片段中的覆盖比例。
     *
     * @param terms 查询词项
     * @param text  片段原文
     * @return 0~1，命中词项数除以总词项数
     */
    private double coverage(List<String> terms, String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String term : terms) {
            if (tokenizer.containsTerm(text, term)) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    /**
     * 计算标题匹配度。
     *
     * @param terms 查询词项
     * @param title 文档标题
     * @return 1 表示标题命中了查询词，否则 0
     */
    private double titleHit(List<String> terms, String title) {
        if (title == null || title.isEmpty()) {
            return 0.0;
        }
        for (String term : terms) {
            if (tokenizer.containsTerm(title, term)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * 把分数裁剪到 0~1。
     *
     * @param score 原始分
     * @return 裁剪后的分数
     */
    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
