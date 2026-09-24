package com.mewchat.dao.milvus;

import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Milvus 向量库访问封装：插入向量、相似度检索、删除。
 *
 * <p><b>只做存取，不含业务判断</b>：不写提示词、不做重排、不决定"检索到了该不该用"，
 * 这些都是 {@code rag} 层的事。本类负责的是把知识切片可靠地放进向量库，
 * 以及按语义把它捞出来。
 *
 * <p><b>为什么包在 {@link EmbeddingStore} 上而不是直接包官方 {@code MilvusServiceClient}</b>：
 * {@code langchain4j-milvus} 的 {@code MilvusEmbeddingStore} 已经实现了建表、插入、
 * 检索、按元数据删除的完整逻辑，并且实现了 LangChain4j 的 {@code EmbeddingStore} 接口。
 * 基于它封装可以直接接入 LangChain4j 的 RAG 链路；若基于官方 SDK 重写一遍，
 * 既重复劳动，又拿不到 LangChain4j 生态的检索器与过滤器。
 * 官方的 {@code MilvusServiceClient} Bean 依然由 {@code MilvusConfig} 提供，
 * 需要做 collection 级运维（如建索引、改配置）时可以直接注入使用。
 *
 * <p><b>注意本类不是 {@code @Component}</b>：它的存在依赖 {@code EmbeddingStore}，
 * 而后者受 {@code mewchat.milvus.enabled} 控制。为避免"条件写在两处、改漏一处就启不动"，
 * 统一由 {@code MilvusConfig} 声明为 Bean。
 *
 * @author MewChat
 */
public class MilvusVectorDao {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorDao.class);

    /* ==================== 元数据键名 ==================== */
    /* LangChain4j 的 Milvus 集合只有 id/text/metadata/vector 四个字段，
       自定义数据一律放 metadata(JSON)，键名集中在此，rag 层做过滤时复用。 */

    /** 来源文档ID，对应 knowledge_document.id。<b>以字符串存储</b> */
    public static final String META_DOC_ID = "doc_id";

    /** 段落号，从 1 开始 */
    public static final String META_CHUNK_NO = "chunk_no";

    /** 文档标题，检索结果可直接展示，省一次回表 */
    public static final String META_DOC_TITLE = "doc_title";

    /** 知识分类，检索时可按分类过滤 */
    public static final String META_CATEGORY = "category";

    /** Milvus VARCHAR 字段的字符数上限，超长会导致插入失败 */
    private static final int MILVUS_VARCHAR_MAX_LENGTH = 65535;

    private final EmbeddingStore<TextSegment> embeddingStore;

    private final EmbeddingModel embeddingModel;

    /**
     * @param embeddingStore 向量库存储实现（由 MilvusConfig 构建）
     * @param embeddingModel 文本向量化模型，用于把切片与查询词转成向量
     */
    public MilvusVectorDao(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 把一个文档的切片批量向量化并写入向量库。
     *
     * <p>切片ID采用确定性规则 {@code {docId}_{chunkNo}}，而不是随机 UUID。
     * 这样同一文档重新向量化时，配合 {@link #deleteByDocId(Long)} 先删后插即可保证幂等，
     * 不会产生重复分片。
     *
     * @param docId    文档ID，对应 knowledge_document.id
     * @param docTitle 文档标题，写入元数据供检索结果直接展示
     * @param category 知识分类，写入元数据供按分类过滤
     * @param chunks   切片文本列表，顺序即段落号顺序（从 1 开始）
     * @return 写入成功的分片ID列表
     * @throws BizException 切片文本为空或超出 Milvus VARCHAR 上限时抛出
     */
    public List<String> addChunks(Long docId, String docTitle, String category, List<String> chunks) {
        if (docId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "docId 不能为空");
        }
        if (CollectionUtils.isEmpty(chunks)) {
            log.warn("文档 {} 没有可入库的切片，跳过", docId);
            return List.of();
        }
        // 先校验再向量化：宁可整批失败并暴露问题，也不静默截断——
        // 悄悄截断会悄悄降低检索质量，比报错难查得多
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (!StringUtils.hasText(chunk)) {
                throw new BizException(ResultCode.PARAM_INVALID,
                        "文档 " + docId + " 的第 " + (i + 1) + " 个切片为空");
            }
            if (chunk.length() > MILVUS_VARCHAR_MAX_LENGTH) {
                throw new BizException(ResultCode.VECTOR_STORE_ERROR,
                        "文档 " + docId + " 的第 " + (i + 1) + " 个切片长度 " + chunk.length()
                                + " 超过向量库字段上限 " + MILVUS_VARCHAR_MAX_LENGTH + "，请调小切片尺寸");
            }
        }

        List<String> ids = new ArrayList<>(chunks.size());
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            int chunkNo = i + 1;
            Metadata metadata = new Metadata()
                    // doc_id 存字符串而非数字：Milvus 的 JSON 数值按 double 存储，
                    // 19 位雪花ID会丢精度，导致按 doc_id 删除时匹配不上
                    .put(META_DOC_ID, String.valueOf(docId))
                    .put(META_CHUNK_NO, chunkNo)
                    .put(META_CATEGORY, category == null ? "" : category);
            if (docTitle != null) {
                metadata.put(META_DOC_TITLE, docTitle);
            }
            segments.add(TextSegment.from(chunks.get(i), metadata));
            ids.add(buildChunkId(docId, chunkNo));
        }

        Response<List<Embedding>> embeddingResponse = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = embeddingResponse.content();
        if (embeddings == null || embeddings.size() != segments.size()) {
            throw new BizException(ResultCode.RAG_ERROR,
                    "向量化结果数量与切片数量不一致：期望 " + segments.size()
                            + " 实际 " + (embeddings == null ? 0 : embeddings.size()));
        }

        embeddingStore.addAll(ids, embeddings, segments);
        log.info("文档 {} 向量入库完成，共 {} 个切片", docId, ids.size());
        return ids;
    }

    /**
     * 按语义检索知识切片。
     *
     * @param query    用户查询文本（原始问句即可，是否改写由 rag 层决定）
     * @param topK     返回条数上限
     * @param minScore 相似度下限，低于该值的直接丢弃；取值区间取决于度量方式，
     *                 COSINE 为 0~1
     * @return 检索结果，含命中片段与其得分
     */
    public EmbeddingSearchResult<TextSegment> search(String query, int topK, double minScore) {
        return search(query, topK, minScore, null);
    }

    /**
     * 按语义检索知识切片，并可按知识分类过滤。
     *
     * @param query    用户查询文本
     * @param topK     返回条数上限
     * @param minScore 相似度下限
     * @param category 知识分类；为空则不限分类
     * @return 检索结果
     */
    public EmbeddingSearchResult<TextSegment> search(String query, int topK, double minScore, String category) {
        if (!StringUtils.hasText(query)) {
            throw new BizException(ResultCode.PARAM_INVALID, "检索内容不能为空");
        }

        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore);
        if (StringUtils.hasText(category)) {
            requestBuilder.filter(metadataKey(META_CATEGORY).isEqualTo(category));
        }

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(requestBuilder.build());
        log.debug("向量检索完成：query='{}' category={} 命中 {} 条",
                query, category, result.matches().size());
        return result;
    }

    /**
     * 删除某个文档的全部切片。
     *
     * <p>用于"文档更新后重建索引"与"文档删除"两条路径。
     * 必须先删后插，否则确定性分片ID会与旧数据冲突。
     *
     * @param docId 文档ID
     */
    public void deleteByDocId(Long docId) {
        if (docId == null) {
            return;
        }
        embeddingStore.removeAll(metadataKey(META_DOC_ID).isEqualTo(String.valueOf(docId)));
        log.info("已删除文档 {} 的全部向量切片", docId);
    }

    /**
     * 批量删除多个文档的全部切片。
     *
     * <p>用一趟 {@code in} 过滤完成，避免 N 次往返。
     *
     * @param docIds 文档ID集合；为空则不做任何操作
     */
    public void deleteByDocIds(Collection<Long> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return;
        }
        List<String> ids = docIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        embeddingStore.removeAll(metadataKey(META_DOC_ID).isIn(ids));
        log.info("已批量删除 {} 个文档的向量切片", ids.size());
    }

    /**
     * 构造确定性分片ID。
     *
     * @param docId   文档ID
     * @param chunkNo 段落号，从 1 开始
     * @return 形如 {@code 1727138400000000001_3} 的分片ID
     */
    public static String buildChunkId(Long docId, int chunkNo) {
        return docId + "_" + chunkNo;
    }
}
