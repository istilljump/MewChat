package com.mewchat.rag;

import com.mewchat.config.AgentProperties;
import com.mewchat.config.RagProperties;
import com.mewchat.rag.rerank.HeuristicReranker;
import com.mewchat.rag.retrieval.ChunkTokenizer;
import com.mewchat.rag.retrieval.RetrievalConfidenceCalculator;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.rag.retrieval.RrfFusion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 置信度上限的下界：无关召回不能走到"直接回复"。
 *
 * <p><b>要验的是什么</b>：RRF 按当次最高分归一化，因此排名第一的候选融合分恒为 1.0 ——
 * 这个分只表达"在此次召回里排第几"，<b>不表达绝对相关性</b>。于是"召回了一堆
 * 和问题毫无关系的片段"也可能拿到一个不低的分数。本类把这条链路走完整
 * （融合 → 重排 → 置信度），断言这种最坏情况仍然落在达标线以下。
 *
 * <p><b>为什么用断言而不是改算法</b>：把绝对相关性找回来意味着要么引入
 * 交叉编码器打分、要么给 BM25 定一个跨语料可比的绝对阈值，两者都得先拿真实数据看收益；
 * 而按"词面无重叠"直接否决又会让"语义相近但用词不同"的召回全被误杀 ——
 * 那恰恰是向量通道存在的理由，且在 Milvus 未启用的默认配置下等于把关键词匹配之外的
 * 一切都砍掉。因此这里先<b>钉住安全性质</b>：无关召回可以多跑一次补检索，
 * 但绝不能进入 REPLY。等真实数据到手再谈口径调整。
 *
 * @author MewChat
 */
class RagConfidenceCeilingTest {

    /** 与下方片段文本<b>没有任何二元词重叠</b>的提问 */
    private static final String NO_OVERLAP_QUERY = "退货政策";

    /** 与提问无重叠的片段文本（二元词集合与上面完全不交） */
    private static final String NO_OVERLAP_TEXT = "本店发票开具流程";

    private final RagProperties ragProperties = new RagProperties();

    private final RrfFusion rrfFusion = new RrfFusion();

    private final HeuristicReranker reranker = new HeuristicReranker(new ChunkTokenizer());

    private final RetrievalConfidenceCalculator calculator =
            new RetrievalConfidenceCalculator(ragProperties);

    /**
     * 三条无关候选：置信度必须低于达标线，不能直接回复。
     *
     * <p>算一遍账（默认权重：融合 0.5 / 覆盖度 0.4 / 标题 0.1；置信度：topScore 0.7
     * + countWeight 0.3，expectedChunkCount=3，relevanceFloor=0.5）：
     * <ul>
     *     <li>单通道召回的 RRF 原始分是 1/61、1/62、1/63，彼此极接近 ——
     *         归一化后分别是 1.0、0.984、0.968</li>
     *     <li>重排后融合项 ×0.5 得 0.5、0.492、0.484，覆盖度与标题都是 0</li>
     *     <li>下限 0.5 是<b>含等号</b>的，因此只有头部那一条（正好 0.5）被算作有效片段，
     *         另外两条落在下限之下不算 —— 这一点很关键：
     *         "排第一"本身不构成相关性证据</li>
     *     <li>置信度 = 0.7×0.5 + 0.3×(1/3) = <b>0.45</b></li>
     * </ul>
     * 0.45 落在中档（0.40~0.70）：会多跑一次补检索，随后走兜底。
     * 代价是一次多余的检索，而不是拿无关片段组织出一段像样的回答。
     */
    @Test
    void irrelevantChunksShouldNeverReachReply() {
        BigDecimal confidence = confidenceOf(List.of(chunk("c1"), chunk("c2"), chunk("c3")));

        assertThat(confidence)
                .as("无关召回必须落在达标线以下，否则系统会拿无关内容作答")
                .isLessThan(new AgentProperties().getHighConfidenceThreshold());
        assertThat(confidence)
                .as("按当前权重应为 0.45：排第一不构成相关性证据，计数项只拿到 1/3")
                .isEqualByComparingTo("0.4500");
    }

    /**
     * 只有一条无关候选时，结果同样是 0.45 —— 不能因为"没有别的候选"就显得可信。
     *
     * <p>这正是把归一化写进断言的价值：候选从三条减到一条，分数<b>一点没变</b>，
     * 因为 RRF 只看排名、计数项又都只算一条有效片段。
     */
    @Test
    void singleIrrelevantChunkShouldNotReachReply() {
        BigDecimal confidence = confidenceOf(List.of(chunk("c1")));

        assertThat(confidence)
                .as("单条无关召回不能因为'没有别的候选'就显得可信")
                .isLessThan(new AgentProperties().getHighConfidenceThreshold());
        assertThat(confidence).isEqualByComparingTo("0.4500");
    }

    /**
     * 走完融合 → 重排 → 置信度这条真实链路，返回置信度。
     *
     * @param candidates 候选片段（按各自通道的排名顺序）
     * @return 置信度
     */
    private BigDecimal confidenceOf(List<RetrievedChunk> candidates) {
        // 只走关键词通道：Milvus 默认未启用，这正是线上的默认形态
        List<RetrievedChunk> fused = rrfFusion.fuse(List.of(), candidates, ragProperties.getRrf().getK());
        List<RetrievedChunk> reranked = reranker.rerank(NO_OVERLAP_QUERY, fused, ragProperties.getTopK());
        return calculator.calculate(reranked);
    }

    /**
     * 构造一条与提问无任何二元词重叠的候选片段。
     *
     * @param chunkId 片段标识
     * @return 候选片段
     */
    private static RetrievedChunk chunk(String chunkId) {
        return RetrievedChunk.builder()
                .chunkId(chunkId)
                .docId(1L)
                .chunkNo(1)
                .text(NO_OVERLAP_TEXT)
                .bm25Score(0.01)
                .sources("bm25")
                .build();
    }
}
