package com.mewchat.rag.retrieval;

import com.mewchat.config.RagProperties;
import com.mewchat.dao.mysql.entity.KnowledgeChunk;
import com.mewchat.service.KnowledgeChunkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 关键词召回通道。
 *
 * <p><b>为什么要这一路而不仅有向量检索</b>：向量检索擅长语义相近但用词不同的情况，
 * 却在"精确匹配"上很弱 —— 用户问"订单号 202601 的物流"，或者问某个具体型号，
 * 向量召回可能因为整体语义漂移而漏掉唯一那篇正确文档。
 * 关键词通道对这类查询是决定性的。
 *
 * <p><b>为什么用 MySQL 而不是 ES</b>：知识库规模在本项目的量级（几千到几万切片），
 * MySQL 的 ngram 全文索引足够，省掉一个中间件。代价是 BM25 需要自己在 Java 侧算 ——
 * MySQL 的 {@code MATCH ... AGAINST} 给的是 TF-IDF 风格的相对分，不是 BM25。
 *
 * <p><b>打分流程</b>：
 * <ol>
 *     <li>分词（与索引的 2-gram 粒度对齐）</li>
 *     <li>从 MySQL 取语料统计 N 与平均长度 avgdl</li>
 *     <li>逐个词统计文档频率 df（走全文索引，不是 LIKE 全表扫）</li>
 *     <li>用全文索引召回候选切片</li>
 *     <li>在 Java 侧按标准 BM25 公式对候选打分并排序</li>
 * </ol>
 *
 * @author MewChat
 */
@Component
public class Bm25Retriever {

    private static final Logger log = LoggerFactory.getLogger(Bm25Retriever.class);

    private final KnowledgeChunkService chunkService;

    private final ChunkTokenizer tokenizer;

    private final RagProperties ragProperties;

    public Bm25Retriever(KnowledgeChunkService chunkService,
                         ChunkTokenizer tokenizer,
                         RagProperties ragProperties) {
        this.chunkService = chunkService;
        this.tokenizer = tokenizer;
        this.ragProperties = ragProperties;
    }

    /**
     * 按关键词召回并打分。
     *
     * @param query 查询文本
     * @param topK  返回条数上限
     * @return 命中片段，按 BM25 分值降序；查询词无效或语料为空时返回空列表
     */
    public List<RetrievedChunk> retrieve(String query, int topK) {
        RagProperties.Bm25 config = ragProperties.getBm25();
        List<String> terms = tokenizer.tokenize(query, config.getMaxQueryTerms());
        if (terms.isEmpty()) {
            log.debug("查询分词结果为空，关键词通道跳过：query={}", query);
            return List.of();
        }

        long totalChunks = chunkService.countAllChunks();
        if (totalChunks == 0) {
            return List.of();
        }
        // avgdl 为 0 会出现在分母上，至少取 1
        double avgLength = Math.max(1.0, chunkService.averageContentLength());

        Map<String, Long> documentFrequencies = new HashMap<>(terms.size());
        for (String term : terms) {
            documentFrequencies.put(term, chunkService.countByTerm(term));
        }

        List<KnowledgeChunk> candidates = chunkService.searchByKeyword(
                tokenizer.buildBooleanQuery(terms), config.getMaxCandidates());
        if (CollectionUtils.isEmpty(candidates)) {
            return List.of();
        }

        List<RetrievedChunk> scored = new ArrayList<>(candidates.size());
        for (KnowledgeChunk candidate : candidates) {
            double score = score(candidate, terms, documentFrequencies, totalChunks, avgLength, config);
            // 分值为 0 说明虽然被全文索引召回，但没有任何一个查询词真正贡献了权重
            // （例如只命中了被过滤掉的停用词），保留它只会污染融合结果
            if (score <= 0) {
                continue;
            }
            scored.add(RetrievedChunk.builder()
                    .chunkId(RetrievedChunk.chunkKey(candidate.getDocId(), candidate.getChunkNo()))
                    .docId(candidate.getDocId())
                    .chunkNo(candidate.getChunkNo())
                    .text(candidate.getContent())
                    .bm25Score(score)
                    .sources("bm25")
                    .build());
        }

        scored.sort(Comparator.comparingDouble(RetrievedChunk::getBm25Score).reversed());
        List<RetrievedChunk> result = scored.size() > topK ? scored.subList(0, topK) : scored;
        log.debug("BM25 召回：query='{}' terms={} 候选 {} 命中 {}",
                query, terms, candidates.size(), result.size());
        return new ArrayList<>(result);
    }

    /**
     * 按标准 BM25 公式计算单个切片的得分。
     *
     * <pre>
     *   score = Σ_t IDF(t) · tf·(k1+1) / (tf + k1·(1 - b + b·dl/avgdl))
     *   IDF(t) = ln(1 + (N - df + 0.5) / (df + 0.5))
     * </pre>
     *
     * <p>{@code k1} 控制词频饱和速度（调大则高频词影响更持久），
     * {@code b} 控制长度归一化强度（0 完全不归一化，1 完全归一化）。
     *
     * @param chunk               候选切片
     * @param terms               查询词项
     * @param documentFrequencies 各词项的文档频率
     * @param totalChunks         语料总量 N
     * @param avgLength           平均长度 avgdl
     * @param config              BM25 参数
     * @return BM25 得分，非负
     */
    private double score(KnowledgeChunk chunk,
                         List<String> terms,
                         Map<String, Long> documentFrequencies,
                         long totalChunks,
                         double avgLength,
                         RagProperties.Bm25 config) {
        String content = chunk.getContent();
        if (content == null || content.isEmpty()) {
            return 0.0;
        }

        int docLength = chunk.getContentLength() != null
                ? chunk.getContentLength()
                : content.length();
        double lengthNorm = 1 - config.getB() + config.getB() * (docLength / avgLength);

        double total = 0.0;
        for (String term : terms) {
            int termFrequency = countOccurrences(content, term);
            if (termFrequency == 0) {
                continue;
            }
            long documentFrequency = documentFrequencies.getOrDefault(term, 0L);
            double idf = inverseDocumentFrequency(totalChunks, documentFrequency);
            total += idf * (termFrequency * (config.getK1() + 1))
                    / (termFrequency + config.getK1() * lengthNorm);
        }
        return total;
    }

    /**
     * 计算词项逆文档频率。
     *
     * <p>用 {@code ln(1 + ...)} 这种写法而不是经典的 {@code ln((N-df+0.5)/(df+0.5))}：
     * 前者在 df 很大时也不会算出负值，避免"常见词反而拉低总分"的怪异行为。
     *
     * @param totalChunks      语料总量
     * @param documentFrequency 文档频率
     * @return IDF，恒为正
     */
    private double inverseDocumentFrequency(long totalChunks, long documentFrequency) {
        double numerator = totalChunks - documentFrequency + 0.5;
        double denominator = documentFrequency + 0.5;
        return Math.log(1 + Math.max(numerator, 0.0) / denominator);
    }

    /**
     * 统计词项在文本中的出现次数（允许重叠）。
     *
     * <p>重叠计数对 2-gram 是合理的："退货退货"里"退货"出现 2 次，
     * 确实比只出现 1 次更相关。
     *
     * @param text 文本
     * @param term 词项
     * @return 出现次数
     */
    private int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index++;
        }
        return count;
    }
}
