package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识切片表 Mapper。
 *
 * <p>BM25 需要三类查询，都不是单表 CRUD 能表达的，因此手写 SQL 放在
 * {@code resources/mapper/KnowledgeChunkMapper.xml}（遵循"手写 SQL 一律进 XML"的约定）：
 * <ul>
 *     <li>候选召回 —— 用 ngram 全文索引按关键词捞候选</li>
 *     <li>文档频率统计 —— BM25 公式里的 df</li>
 *     <li>语料统计 —— BM25 公式里的 N 与平均文档长度</li>
 * </ul>
 *
 * <p>为什么不在数据库里直接算 BM25：MySQL 的 {@code MATCH ... AGAINST} 给出的是
 * TF-IDF 风格的相对分，不是 BM25。要拿到真正的 BM25，必须自己控制
 * 词频统计与长度归一化，因此这里只把 MySQL 当作<b>可走索引的候选源与统计源</b>，
 * 打分在 Java 侧完成（见 {@code rag.retrieval.Bm25Retriever}）。
 *
 * @author MewChat
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 按关键词召回候选切片。
     *
     * @param booleanQuery BOOLEAN MODE 查询串，多个词以空格分隔（默认 OR 语义）
     * @param limit        候选条数上限
     * @return 候选切片，未按相关性排序（排序由 Java 侧的 BM25 负责）
     */
    List<KnowledgeChunk> searchByKeyword(@Param("booleanQuery") String booleanQuery,
                                         @Param("limit") int limit);

    /**
     * 统计包含指定词的切片数量，即 BM25 的文档频率 df。
     *
     * <p>用全文索引做计数而不是 {@code LIKE '%term%'}：
     * 后者无法走索引，每个词都要全表扫描一次。
     *
     * @param term 查询词（与建索引时的 ngram 粒度一致，通常是 2 元切分）
     * @return 包含该词的切片数
     */
    long countByTerm(@Param("term") String term);

    /**
     * 统计全部切片数量，即 BM25 的语料总量 N。
     *
     * @return 切片总数
     */
    long countAllChunks();

    /**
     * 统计切片平均字符数，即 BM25 的长度归一化基准 avgdl。
     *
     * @return 平均长度，无数据时返回 0
     */
    double averageContentLength();
}
