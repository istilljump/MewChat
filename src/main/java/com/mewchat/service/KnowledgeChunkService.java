package com.mewchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mewchat.dao.mysql.entity.KnowledgeChunk;

import java.util.List;

/**
 * 知识切片业务服务。
 *
 * <p>对外提供两类能力：BM25 所需的候选召回与语料统计，以及切片的整体替换。
 *
 * @author MewChat
 */
public interface KnowledgeChunkService extends IService<KnowledgeChunk> {

    /**
     * 按关键词召回候选切片。
     *
     * @param booleanQuery BOOLEAN MODE 查询串，多个词以空格分隔
     * @param limit        候选条数上限
     * @return 候选切片
     */
    List<KnowledgeChunk> searchByKeyword(String booleanQuery, int limit);

    /**
     * 统计包含指定词的切片数（BM25 的 df）。
     *
     * @param term 查询词
     * @return 包含该词的切片数
     */
    long countByTerm(String term);

    /**
     * 统计切片总数（BM25 的 N）。
     *
     * @return 切片总数
     */
    long countAllChunks();

    /**
     * 统计切片平均长度（BM25 的 avgdl）。
     *
     * @return 平均字符数
     */
    double averageContentLength();

    /**
     * 用新的切片集合整体替换某个文档的切片。
     *
     * <p>先物理删除旧切片再批量插入：文档重新分片后段落号会整体变化，
     * 逐条比对更新没有意义，整体替换更简单也更不容易出错。
     *
     * @param docId  文档ID
     * @param chunks 新切片集合，可为空（表示该文档切不出有效内容）
     */
    void replaceChunks(Long docId, List<KnowledgeChunk> chunks);

    /**
     * 物理删除某个文档的全部切片。
     *
     * @param docId 文档ID
     */
    void deleteByDocId(Long docId);
}
