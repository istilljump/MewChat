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
 * <p><b>关于实现</b>：现有两个实现，由 {@code RerankConfig} 按
 * {@code mewchat.rag.rerank.mode} 二选一装配：
 * {@link HeuristicReranker} 规则式（词项覆盖度 + 标题匹配 + 融合分加权，零成本、确定，
 * 默认）；{@link LlmReranker} 让大模型按语义给候选打相关性分（更准，每次检索
 * 多一次模型调用，任何失败降级为"不重排"）。本接口是替换点 ——
 * 换交叉编码器（bge-reranker 之类）时同样只需新增实现类，{@code RagService} 不改。
 *
 * <p>无论哪个实现，都要守住同一组不变量：失败不抛异常、返回恒为候选子集、
 * {@code score} 归一化到 0~1（它是置信度计算的输入）、无关内容不得拿到高分
 * （否则"排第一不足以作答"的防线会被绕过）。
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
