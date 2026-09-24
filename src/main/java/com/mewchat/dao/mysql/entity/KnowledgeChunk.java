package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 知识切片表实体，对应 {@code knowledge_chunk} 表。
 *
 * <p>切片在系统里存两份，各司其职：
 * <ul>
 *     <li><b>本表</b> —— 关键词（BM25）召回的数据源，同时提供段落号用于溯源</li>
 *     <li><b>Milvus</b> —— 语义（向量）召回的数据源</li>
 * </ul>
 * 两侧靠 (docId, chunkNo) 对齐。
 *
 * <p><b>本表刻意没有 {@code deleted} 字段</b>：切片是由文档全文派生的数据，
 * 重新入库时整体物理删除再插入。若用逻辑删除，{@code uk_doc_chunk} 唯一键
 * 会被软删的行占住，同一文档再也无法重新入库。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文档ID，对应 knowledge_document.id */
    private Long docId;

    /** 段落号，从 1 开始，与向量库元数据里的 chunk_no 一致 */
    private Integer chunkNo;

    /** 切片原文，BM25 关键词检索的对象 */
    private String content;

    /** 切片在文档全文中的起始偏移，供前端高亮定位 */
    private Integer charStart;

    /** 切片在文档全文中的结束偏移 */
    private Integer charEnd;

    /**
     * 切片字符数。
     *
     * <p>冗余字段：BM25 的文档长度归一化需要它，每次现算 CHAR_LENGTH
     * 会让统计查询无法走索引。
     */
    private Integer contentLength;

    /** 创建时间，插入时自动填充。本表无更新时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
