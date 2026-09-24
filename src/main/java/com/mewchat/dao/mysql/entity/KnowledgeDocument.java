package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 知识库文档表实体，对应 {@code knowledge_document} 表。
 *
 * <p>{@code content} 存的是文档<b>全文</b>，是"切片 → 向量化"的源头。
 * 之所以不直接存切片结果，是为了让切片策略可以随时调整：
 * 改完只需重跑向量化，不必让用户重新上传文档。
 *
 * <p>本表主键 {@code id} 同时会以字符串形式写入向量库的 {@code metadata.doc_id}，
 * 用于按文档批量删除/重建分片。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_document")
public class KnowledgeDocument {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 文档标题 */
    private String title;

    /** 文档全文（切片与向量化的数据源） */
    private String content;

    /** 知识分类，检索时可据此过滤 */
    private String category;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型：pdf/docx/md/txt */
    private String fileType;

    /** 原始文件大小（字节） */
    private Long fileSize;

    /** 原始文件存储路径 */
    private String filePath;

    /** 切片数量，向量化成功后回写 */
    private Integer chunkCount;

    /**
     * 向量化状态：0待处理 1处理中 2已入库 3失败。
     *
     * <p>切片与向量化必须异步做，没有这个字段就无法回答
     * "用户刚上传的文档，现在到底能不能被检索到"。
     */
    private Integer embedStatus;

    /** 向量化失败原因，仅 embedStatus=3 时有值 */
    private String embedError;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
