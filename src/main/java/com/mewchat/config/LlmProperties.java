package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 大模型相关配置，对应 {@code application.yml} 的 {@code mewchat.llm.*}。
 *
 * <p>为什么不用 LangChain4j 自带的 {@code langchain4j.open-ai.*} 前缀：
 * 本项目由 {@link AiModelConfig} 显式构建模型 Bean（这样才能对 ChatModel、
 * StreamingChatModel、EmbeddingModel 三者做统一控制与校验），
 * 相应地就要关闭 LangChain4j 的自动配置，见 {@code MewChatApplication} 的 {@code exclude}。
 * 配置项随之收归本前缀，避免出现"改了 {@code langchain4j.*} 却不生效"的死配置。
 *
 * <p>各字段都可从环境变量覆盖（yml 中已写成 {@code ${LLM_API_KEY:...}} 形式），
 * 换厂商 / 换模型只改配置不改代码。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.llm")
public class LlmProperties {

    /** 对话模型配置 */
    private Chat chat = new Chat();

    /** 向量化模型配置 */
    private Embedding embedding = new Embedding();

    /**
     * 对话模型配置项。
     *
     * <p>OpenAI 兼容协议，DeepSeek、通义千问（兼容模式）、智谱 GLM 等均可通过
     * 改 {@code baseUrl} + {@code modelName} 切换。
     */
    @Getter
    @Setter
    public static class Chat {

        /** 服务端点，需含 /v1 之类的版本前缀 */
        private String baseUrl;

        /** API 密钥。<b>必须非空</b>，LangChain4j 构建模型时会校验 */
        private String apiKey;

        /** 模型名，如 deepseek-chat */
        private String modelName;

        /** 采样温度。客服场景要求稳定复现，默认取值偏低 */
        private Double temperature = 0.3;

        /** 单次请求超时时间 */
        private Duration timeout = Duration.ofSeconds(60);

        /** 失败重试次数 */
        private Integer maxRetries = 2;

        /** 单次回复的最大 token 数，留空则不限制 */
        private Integer maxCompletionTokens;

        /** 是否打印请求体，仅开发期开启 */
        private boolean logRequests = false;

        /** 是否打印响应体，仅开发期开启 */
        private boolean logResponses = false;
    }

    /**
     * 向量化模型配置项。
     *
     * <p><b>注意</b>：{@code dimensions} 必须与 {@code mewchat.milvus.dimension} 一致，
     * 否则向量写入 Milvus 时会因维度不匹配失败。
     */
    @Getter
    @Setter
    public static class Embedding {

        /** 服务端点 */
        private String baseUrl;

        /** API 密钥，留空则复用对话模型的密钥（多数厂商同一个 key 通用） */
        private String apiKey;

        /** 模型名，如 text-embedding-3-small */
        private String modelName;

        /** 输出向量维度。<b>必须与 Milvus 集合维度一致</b> */
        private Integer dimensions;

        /** 单次请求超时时间 */
        private Duration timeout = Duration.ofSeconds(60);

        /** 失败重试次数 */
        private Integer maxRetries = 2;

        /** 是否打印请求体，仅开发期开启 */
        private boolean logRequests = false;
    }
}
