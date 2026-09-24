package com.mewchat.rag.retrieval;

/**
 * 知识检索服务接口。
 *
 * <p>rag 层对外暴露的入口，编排层的 {@code RagSpecialist} 依赖它。
 * 接口只声明"给定查询文本，返回相关片段与置信度"，<b>不出现任何 agent 层的类型</b>——
 * rag 层不知道意图的存在，只负责检索，两层可以独立演进。
 *
 * <p>实现类为 {@code com.mewchat.rag.RagService}，内部编排
 * 向量检索 → BM25 检索 → RRF 融合 → 重排 → 置信度计算 的完整链路。
 *
 * @author MewChat
 */
public interface KnowledgeRetriever {

    /**
     * 检索知识片段。
     *
     * @param query 查询文本（通常已经过改写与指代消解）
     * @param topK  返回条数上限
     * @return 检索结果，<b>永不为 null</b>；无结果时返回置信度为 0 的空结果
     */
    RetrievalResult retrieve(String query, int topK);
}
