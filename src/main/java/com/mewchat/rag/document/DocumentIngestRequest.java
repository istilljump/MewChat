package com.mewchat.rag.document;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 文档入库请求。
 *
 * <p>用独立的值对象而不是直接传 {@code KnowledgeDocument} 实体：
 * 入库只需要这几个字段，其余（主键、切片数、向量化状态、审计字段）
 * 都由入库流程自己负责，不该由调用方操心。
 *
 * @author MewChat
 */
@Getter
@ToString
@Builder
public class DocumentIngestRequest {

    /** 文档标题 */
    private final String title;

    /** 文档全文 */
    private final String content;

    /** 知识分类，检索时可据此过滤 */
    private final String category;

    /** 原始文件名，手工录入文本时为 null */
    private final String fileName;

    /** 文件类型：pdf/docx/md/txt */
    private final String fileType;

    /** 原始文件大小（字节） */
    private final Long fileSize;

    /** 原始文件存储路径 */
    private final String filePath;
}
