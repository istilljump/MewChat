package com.mewchat.rag.rerank;

import com.mewchat.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 重排器：对融合后的候选做二次精排。
 *
 * <p><b>为什么融合之后还要重排</b>：RRF 只看排名不看内容 ——
 * 它能把"两路都认可"的片段排到前面，但无法判断"这段文字是真的在回答这个问题，
 * 还是只是词面相近"。重排这一步直接阅读查询与片段内容本身，做更精细的相关性判断，
 * 是提升最终答案质量最有效的一环。
 *
 * <p><b>关于当前实现</b>：默认实现 {@link HeuristicReranker} 是规则式的
 * （词项覆盖度 + 标题匹配 + 融合分加权），不依赖额外模型。
 * 它是<b>可替换的</b>：真正意义上的重排应该用交叉编码器（cross-encoder，
 * 如 bge-reranker）或让大模型直接给相关性打分，效果通常明显更好。
 * 本接口就是那个替换点 —— 接入重排模型时新增一个实现类即可，
 * {@code RagService} 不需要改动。
 *
 * <p>之所以当前不直接上模型重排：交叉编码器需要额外部署一个模型服务，
 * 大模型打分则要为每个候选多付一次调用（延迟与费用都显著上升），
 * 这两件事都应该在真实数据上验证收益后再做，而不是先写进主链路。
 *
 * @author MewChat
 */
public interface Reranker {

    /**
     * 对候选片段重排。
     *
     * @param query    查询文本
     * @param chunks   待重排的候选片段（融合后）
     * @param topN     返回条数上限
     * @return 重排后的片段，按最终分降序，且 {@code score} 已归一化到 0~1
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topN);
}
