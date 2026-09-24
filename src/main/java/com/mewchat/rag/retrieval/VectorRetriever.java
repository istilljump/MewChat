package com.mewchat.rag.retrieval;

import com.mewchat.dao.milvus.MilvusVectorDao;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回通道（Milvus）。
 *
 * <p><b>关于分层</b>：本类直接依赖 {@code dao.milvus.MilvusVectorDao}，
 * 而不是绕一层 service。原因是 {@code MilvusVectorDao} 本身就是纯数据访问、
 * 不含任何业务逻辑，中间再插一层 service 只会是空转发。
 * {@code dao} 是最底层，其上任何一层都可以使用它；
 * 真正要守的规则是"编排层（agent）不直接碰 dao"，那条没有破。
 *
 * <p><b>Milvus 未启用时不是故障</b>：{@code mewchat.milvus.enabled=false} 时
 * 本通道返回空列表，整条检索链路降级为纯关键词（BM25）模式。
 * 这让"还没部署向量库"的阶段也能真实使用知识库问答，
 * 而不是所有问题都掉进兜底。
 *
 * @author MewChat
 */
@Component
public class VectorRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);

    private final ObjectProvider<MilvusVectorDao> vectorDaoProvider;

    public VectorRetriever(ObjectProvider<MilvusVectorDao> vectorDaoProvider) {
        this.vectorDaoProvider = vectorDaoProvider;
    }

    /**
     * 按语义召回。
     *
     * @param query    查询文本
     * @param topK     返回条数上限
     * @param minScore 相似度下限
     * @return 命中片段，含原始向量相似度；Milvus 不可用或检索异常时返回空列表
     */
    public List<RetrievedChunk> retrieve(String query, int topK, double minScore) {
        MilvusVectorDao vectorDao = vectorDaoProvider.getIfAvailable();
        if (vectorDao == null) {
            log.debug("Milvus 未启用，向量通道跳过，本次检索降级为仅关键词");
            return List.of();
        }

        try {
            EmbeddingSearchResult<TextSegment> searchResult = vectorDao.search(query, topK, minScore);
            return convert(searchResult);
        } catch (Exception e) {
            // 向量库不可用不应让整轮问答失败：关键词通道仍能给出结果
            log.error("向量检索失败，降级为仅关键词检索：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 把向量库返回结果转换为统一片段模型。
     *
     * <p>文档标题不在这里取：关键词通道的结果里没有标题，
     * 统一由 {@code RagService} 在合并后按文档ID批量补全，
     * 避免一条链路查一次库。
     *
     * @param searchResult 向量检索结果
     * @return 命中片段
     */
    private List<RetrievedChunk> convert(EmbeddingSearchResult<TextSegment> searchResult) {
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> chunks = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (segment == null) {
                continue;
            }
            Long docId = readDocId(segment.metadata());
            Integer chunkNo = segment.metadata().getInteger(MilvusVectorDao.META_CHUNK_NO);
            if (docId == null || chunkNo == null) {
                // 元数据缺失说明这条向量不是本系统写入的，跳过而不是把脏数据带进回答
                log.warn("向量命中缺少 doc_id 或 chunk_no 元数据，已跳过：{}", match.embeddingId());
                continue;
            }

            chunks.add(RetrievedChunk.builder()
                    .chunkId(RetrievedChunk.chunkKey(docId, chunkNo))
                    .docId(docId)
                    .chunkNo(chunkNo)
                    .text(segment.text())
                    .vectorScore(match.score())
                    .sources("vector")
                    .build());
        }
        return chunks;
    }

    /**
     * 读取文档ID。
     *
     * <p>doc_id 在向量库里是<b>字符串</b>存储的（19 位雪花ID 存成 JSON 数字会因
     * double 精度丢失尾数，导致删除时匹配不上），这里解析回 Long。
     *
     * @param metadata 元数据
     * @return 文档ID，缺失或非法时返回 null
     */
    private Long readDocId(Metadata metadata) {
        String raw = metadata.getString(MilvusVectorDao.META_DOC_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("向量元数据里的 doc_id 不是合法数字：{}", raw);
            return null;
        }
    }
}
