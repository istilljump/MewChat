package com.mewchat.rag.retrieval;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次检索的完整结果。
 *
 * <p>除了片段本身，还带上<b>置信度</b>与<b>各通道命中数</b>。
 * 置信度由 rag 层计算并对外提供，而不是让编排层拿最高分自己判断 ——
 * 置信度的算法（最高分 + 命中数量综合）属于检索质量的一部分，
 * 应该和检索实现放在一起演进。
 *
 * <p>命中数用于排查"这条链路到底有没有在干活"：
 * 比如置信度很低时，看 {@code vectorHitCount} 与 {@code bm25HitCount}
 * 就能立刻区分是"知识库里确实没有"还是"某个通道挂了"。
 *
 * @author MewChat
 */
@Getter
@ToString
@Builder
public class RetrievalResult {

    /** 检索用的查询文本（已改写/消解） */
    private final String query;

    /** 最终返回的片段，按相关性降序 */
    private final List<RetrievedChunk> chunks;

    /**
     * 置信度，0~1。
     *
     * <p>综合"最高分"与"命中片段数量"得出：只有一条高分片段，与有三条
     * 互相印证的高分片段，可信程度是不一样的。
     */
    private final BigDecimal confidence;

    /** 向量通道召回的条数（未启用或全部被过滤时为 0） */
    private final int vectorHitCount;

    /** BM25 通道召回的条数 */
    private final int bm25HitCount;

    /**
     * 构造空结果。
     *
     * <p>用于"检索不可用"或"确实没找到"的场景，置信度 0、片段为空。
     * 这样调用方不必判空，统一按"没检索到"处理。
     *
     * @param query 查询文本
     * @return 空结果
     */
    public static RetrievalResult empty(String query) {
        return RetrievalResult.builder()
                .query(query)
                .chunks(List.of())
                .confidence(BigDecimal.ZERO)
                .vectorHitCount(0)
                .bm25HitCount(0)
                .build();
    }

    /**
     * 是否没有检索到任何片段。
     *
     * @return true 表示无结果
     */
    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }
}
