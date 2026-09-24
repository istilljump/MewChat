package com.mewchat.dao.mysql.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 消息引用知识片段的 JSON 值对象。
 *
 * <p><b>这不是一张表</b>，而是 {@link Message#getRefDocs()} 这个 JSON 列的元素结构，
 * 用于把 RAG 检索到的知识片段随消息一起落库，便于前端展示"参考来源"
 * 以及事后排查"这个回答到底引用了什么"。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageRefDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 知识切片在向量库中的主键（即 {@code {docId}_{chunkNo}}）。
     *
     * <p>用字符串而非数字：雪花ID有 19 位，而 Milvus 的 JSON 数值按 double 存储、
     * JS 的 Number 也只有 53 位精度，存成数字会丢精度。
     */
    private String chunkId;

    /** 来源文档ID，对应 knowledge_document.id */
    private Long docId;

    /** 文档标题 */
    private String docTitle;

    /** 该片段在文档内的段落号（从 1 开始） */
    private Integer chunkNo;

    /** 向量检索的相似度得分，保留原始分值便于调参 */
    private Double score;

    /**
     * 该片段最终是否被采用：1采用 0未采用。
     *
     * <p>召回 TopK 后可能被重排序或规则过滤掉，记录下来才能区分
     * "召回了但没用"和"根本没召回"这两种完全不同的失败原因。
     */
    private Integer used;
}
