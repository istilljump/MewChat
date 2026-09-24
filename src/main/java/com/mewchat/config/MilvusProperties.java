package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 向量库配置，对应 {@code application.yml} 的 {@code mewchat.milvus.*}。
 *
 * <p><b>默认 {@code enabled=false}</b>：本机尚未部署 Milvus，关掉可以保证应用正常启动。
 * 部署好 Milvus 2.5.x（注意 SDK 是 2.5.9，服务端版本需对应）后把开关打开即可。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.milvus")
public class MilvusProperties {

    /** 是否启用 Milvus。关闭时不创建任何 Milvus 相关 Bean */
    private boolean enabled = false;

    /** Milvus 服务地址 */
    private String host = "localhost";

    /** Milvus 服务端口 */
    private int port = 19530;

    /** 用户名，未开启鉴权的部署留空即可 */
    private String username;

    /** 密码，未开启鉴权的部署留空即可 */
    private String password;

    /**
     * 集合名。
     *
     * <p>该集合由 LangChain4j 的 {@code MilvusEmbeddingStore} 在首次写入时自动创建，
     * <b>不要手工预建</b>：手工建的 schema 与它期望的不一致会导致插入失败。
     */
    private String collectionName = "mewchat_knowledge";

    /**
     * 向量维度。
     *
     * <p>必须等于向量化模型的输出维度（{@code mewchat.llm.embedding.dimensions}），
     * 写错会导致向量写入失败，且建好的集合无法直接改维度、只能重建。
     */
    private int dimension = 1024;

    /**
     * 索引类型，取值见 Milvus SDK 的 {@code IndexType} 枚举。
     *
     * <p>默认 HNSW：召回质量与延迟的平衡较好，适合本项目的知识库规模。
     */
    private String indexType = "HNSW";

    /**
     * 相似度度量方式，取值见 Milvus SDK 的 {@code MetricType} 枚举。
     *
     * <p>默认 COSINE：文本向量常用度量，且对向量长度不敏感。
     * <b>需与向量化模型的训练度量保持一致</b>，否则检索质量会明显下降。
     */
    private String metricType = "COSINE";

    /** 建立连接的等待超时（毫秒） */
    private long connectTimeoutMs = 10_000L;

    /* ==================== 集合字段名 ==================== */
    /* 这四项是 LangChain4j 的 MilvusEmbeddingStore 唯一允许自定义的部分：
       它只支持 4 个字段（主键/文本/元数据/向量），无法新增标量字段。
       自定义数据（文档id、段落号等）只能放进 metadata JSON，见 MilvusVectorDao。
       此处显式列出默认值，是为了让"集合结构"在配置里一眼可见。 */

    private String idFieldName = "id";

    private String textFieldName = "text";

    private String metadataFieldName = "metadata";

    private String vectorFieldName = "vector";
}
