package com.mewchat.rag;

import com.mewchat.config.RagProperties;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.rag.rerank.Reranker;
import com.mewchat.rag.retrieval.Bm25Retriever;
import com.mewchat.rag.retrieval.KnowledgeRetriever;
import com.mewchat.rag.retrieval.RetrievalConfidenceCalculator;
import com.mewchat.rag.retrieval.RetrievalResult;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.rag.retrieval.RrfFusion;
import com.mewchat.rag.retrieval.VectorRetriever;
import com.mewchat.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索门面 —— 对外只暴露这一个入口。
 *
 * <p>检索链路固定为六步，每一步的职责单一、可独立替换：
 * <pre>
 *   1. 向量召回   VectorRetriever        语义相近，但精确匹配弱
 *   2. 关键词召回 Bm25Retriever          精确匹配强，但不懂同义改写
 *   3. RRF 融合   RrfFusion              只按排名合并，规避两路分数量纲不同的问题
 *   4. 重排       Reranker               直接读内容做二次精排
 *   5. 补全信息   enrichWithDocumentInfo 补文档标题（两路召回的原始结果都没有标题）
 *   6. 置信度     RetrievalConfidenceCalculator  最高分 + 命中数量综合
 * </pre>
 *
 * <p><b>两路召回缺一路也能工作</b>：Milvus 未启用时向量通道返回空，
 * 只靠关键词通道照样能给出结果与置信度。反之知识库里没有中文全文索引也不可能，
 * 因为切片表建表时就带了 ngram 索引。这种设计让"部分组件没就绪"退化成了
 * 检索质量下降，而不是功能不可用。
 *
 * <p><b>为什么要补文档标题</b>：关键词通道的切片来自 MySQL，
 * 表里只有 docId 没有标题；向量通道的元数据里虽然写了标题，
 * 但为了两条路结果结构一致、并顺带过滤掉孤儿数据，统一在这里按文档ID批量补全。
 *
 * @author MewChat
 */
@Service
public class RagService implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorRetriever vectorRetriever;

    private final Bm25Retriever bm25Retriever;

    private final RrfFusion rrfFusion;

    private final Reranker reranker;

    private final RetrievalConfidenceCalculator confidenceCalculator;

    private final KnowledgeDocumentService documentService;

    private final RagProperties ragProperties;

    public RagService(VectorRetriever vectorRetriever,
                      Bm25Retriever bm25Retriever,
                      RrfFusion rrfFusion,
                      Reranker reranker,
                      RetrievalConfidenceCalculator confidenceCalculator,
                      KnowledgeDocumentService documentService,
                      RagProperties ragProperties) {
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
        this.confidenceCalculator = confidenceCalculator;
        this.documentService = documentService;
        this.ragProperties = ragProperties;
    }

    /**
     * 检索知识片段（使用配置里的默认 topK）。
     *
     * @param query 查询文本
     * @return 检索结果，含片段、置信度与各通道命中数
     */
    public RetrievalResult search(String query) {
        return retrieve(query, ragProperties.getTopK());
    }

    @Override
    public RetrievalResult retrieve(String query, int topK) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return RetrievalResult.empty(query);
        }

        // 召回池要比最终需求宽：融合与重排只能在已召回的范围内挑选，
        // 池子太小会把正确结果直接排除在外
        int candidateLimit = Math.max(topK, topK * Math.max(1, ragProperties.getCandidateMultiplier()));

        List<RetrievedChunk> vectorResults = vectorRetriever.retrieve(
                query, candidateLimit, ragProperties.getMinScore());
        List<RetrievedChunk> bm25Results = bm25Retriever.retrieve(query, candidateLimit);

        List<RetrievedChunk> fused = rrfFusion.fuse(
                vectorResults, bm25Results, ragProperties.getRrf().getK());
        if (fused.isEmpty()) {
            log.debug("融合后无候选：query='{}' vector={} bm25={}",
                    query, vectorResults.size(), bm25Results.size());
            return RetrievalResult.empty(query);
        }

        // 补全标题必须在重排【之前】：重排器有一路信号是"标题是否命中查询"
        // （见 HeuristicReranker 的 weight-title），而标题只有查过文档表才有值。
        // 顺序反过来（先重排、再补标题）时，重排看到的 docTitle 恒为 null，
        // 那一路权重永远不生效 —— 不报错、不打日志，只是从没起过作用。
        // 顺带把"来源文档已不存在"的孤儿片段在重排前就剔除，
        // 免得它们占掉 topK 名额、把真正的依据挤出结果集
        List<RetrievedChunk> candidates = enrichWithDocumentInfo(fused);
        List<RetrievedChunk> enriched = reranker.rerank(query, candidates, topK);
        BigDecimal confidence = confidenceCalculator.calculate(enriched);

        log.info("检索完成：query='{}' vector={} bm25={} 融合={} 最终={} 置信度={}",
                query, vectorResults.size(), bm25Results.size(),
                fused.size(), enriched.size(), confidence);

        return RetrievalResult.builder()
                .query(query)
                .chunks(enriched)
                .confidence(confidence)
                .vectorHitCount(vectorResults.size())
                .bm25HitCount(bm25Results.size())
                .build();
    }

    /**
     * 补全文档标题，并顺带过滤掉孤儿片段。
     *
     * <p>顺带过滤是有意为之：若文档已被删除，而 Milvus 里的向量因删除失败残留下来，
     * 检索就会返回一条"查不到来源"的片段。这类内容既不能作为回答依据
     * （无法展示出处），也不该计入置信度，因此在这里直接剔除。
     *
     * @param chunks 重排后的片段
     * @return 补全标题后的片段
     */
    private List<RetrievedChunk> enrichWithDocumentInfo(List<RetrievedChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }

        List<Long> docIds = chunks.stream()
                .map(RetrievedChunk::getDocId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (docIds.isEmpty()) {
            return List.of();
        }

        // 一次批量查询取全部标题，避免每条片段查一次库
        Map<Long, KnowledgeDocument> documentMap = new LinkedHashMap<>();
        for (KnowledgeDocument document : documentService.listByIds(docIds)) {
            documentMap.put(document.getId(), document);
        }

        List<RetrievedChunk> enriched = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            KnowledgeDocument document = documentMap.get(chunk.getDocId());
            if (document == null) {
                log.warn("片段 {} 的来源文档 {} 已不存在，已从检索结果中剔除",
                        chunk.getChunkId(), chunk.getDocId());
                continue;
            }
            chunk.setDocTitle(document.getTitle());
            enriched.add(chunk);
        }
        return enriched;
    }
}
