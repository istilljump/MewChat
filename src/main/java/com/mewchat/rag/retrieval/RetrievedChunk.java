package com.mewchat.rag.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 检索命中的知识片段。
 *
 * <p>RAG 检索的返回单元，同时承担两个职责：
 * <ul>
 *     <li>作为生成回答的依据送进提示词</li>
 *     <li>作为<b>引用来源</b>返回给用户 —— 所以要带全文档标题与段落号</li>
 * </ul>
 *
 * <p>字段里的几种分数各有用途，不要混用：
 * <ul>
 *     <li>{@link #score} —— <b>最终排序分</b>，重排后归一化到 0~1，
 *         既是排序依据，也是置信度计算的输入</li>
 *     <li>{@link #vectorScore} / {@link #bm25Score} —— 两个召回通道的<b>原始分</b>，
 *         仅用于排查"这一段为什么能排到前面"。两者量纲不同，
 *         <b>不能跨通道直接比大小</b></li>
 * </ul>
 *
 * <p>命名上刻意不叫 KnowledgeChunk：MySQL 里另有一张 knowledge_chunk 表和对应实体，
 * 两者同名会让同时用到它们的地方不得不写全限定名。
 * 本类表达"检索到的一段"，实体表达"库里的一行"。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    /** 片段唯一键，形如 {@code {docId}_{chunkNo}}，也是与向量库对齐的键 */
    private String chunkId;

    /** 来源文档ID，对应 knowledge_document.id */
    private Long docId;

    /** 文档标题，作为引用来源直接展示 */
    private String docTitle;

    /** 段落号，从 1 开始 */
    private Integer chunkNo;

    /** 片段原文 */
    private String text;

    /** 最终排序分，0~1 */
    private Double score;

    /** 向量通道原始相似度（COSINE，0~1），未命中该通道时为 null */
    private Double vectorScore;

    /** BM25 通道原始分值，未命中该通道时为 null */
    private Double bm25Score;

    /** 命中来源：vector / bm25 / vector+bm25 */
    private String sources;

    /**
     * 计算片段唯一键。
     *
     * <p>两个召回通道都基于 (docId, chunkNo) 定位同一条切片，
     * 融合时用它去重，避免同一片段因被两个通道同时命中而重复出现。
     *
     * @param docId   文档ID
     * @param chunkNo 段落号
     * @return 形如 {@code 1727138400000000001_3} 的键
     */
    public static String chunkKey(Long docId, Integer chunkNo) {
        return docId + "_" + chunkNo;
    }
}
