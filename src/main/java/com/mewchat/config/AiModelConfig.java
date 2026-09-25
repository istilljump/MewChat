package com.mewchat.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 大模型 Bean 配置：对话模型、流式对话模型、向量化模型。
 *
 * <p><b>为什么需要本类（以及为什么要关掉 LangChain4j 的自动配置）</b>：
 * {@code langchain4j-open-ai-spring-boot-starter} 默认会根据 {@code langchain4j.open-ai.*}
 * 自动创建这些 Bean。如果它与本类同时生效，同一个接口会出现两个 Bean，
 * 注入时产生歧义。因此本项目选择<b>由本类独占模型的创建</b>，
 * 并在 {@code MewChatApplication} 上 exclude 掉
 * {@code dev.langchain4j.openai.spring.AutoConfig}。
 *
 * <p>这样做的收益：
 * <ul>
 *     <li>模型参数集中在一处，便于统一校验与加日志</li>
 *     <li>可以在构建前做友好校验，而不是把 LangChain4j 的内部异常直接抛给启动流程</li>
 *     <li>后续接入重排序模型、本地模型时，扩展点明确</li>
 * </ul>
 * 代价是失去自动配置的属性绑定，需要自己维护 Builder 调用 —— 但这也正是可控性所在。
 *
 * @author MewChat
 */
@Configuration
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    /**
     * application.yml 里给出的占位密钥，与 yml 中的默认值必须保持一致。
     *
     * <p>它存在的意义是"让无模型凭证的环境也能启动"，因此判定逻辑只能告警不能失败
     * （见 {@link #validateApiKey}）。与 yml 默认值写重复是刻意的：
     * 两边一旦不同步，告警就会失效，而失效的表现是"没有告警"—— 无人会注意到。
     */
    private static final String PLACEHOLDER_API_KEY = "sk-REPLACE-WITH-YOUR-REAL-KEY";

    /**
     * 同步对话模型。
     *
     * <p>适用场景：意图识别、会话摘要、工具参数抽取等"可以等结果"的调用。
     * 流式回答请用 {@link #streamingChatModel(LlmProperties)}。
     *
     * @param properties 大模型配置
     * @return 对话模型实例
     */
    @Bean
    public ChatModel chatModel(LlmProperties properties) {
        LlmProperties.Chat chat = properties.getChat();
        validateApiKey(chat.getApiKey(), "mewchat.llm.chat.api-key");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .timeout(chat.getTimeout())
                .maxRetries(chat.getMaxRetries())
                .maxCompletionTokens(chat.getMaxCompletionTokens())
                .logRequests(chat.isLogRequests())
                .logResponses(chat.isLogResponses())
                .build();

        log.info("ChatModel 初始化完成：baseUrl={} model={} temperature={}",
                chat.getBaseUrl(), chat.getModelName(), chat.getTemperature());
        return model;
    }

    /**
     * 流式对话模型，SSE 逐字输出用它。
     *
     * <p>与 {@link #chatModel(LlmProperties)} 共用同一份配置。
     * 两个模型都注册是有意的：同步模型用于内部子任务（摘要、意图识别），
     * 流式模型只用于面向用户的回答，避免把流式回调扩散到不该用的地方。
     *
     * @param properties 大模型配置
     * @return 流式对话模型实例
     */
    @Bean
    public StreamingChatModel streamingChatModel(LlmProperties properties) {
        LlmProperties.Chat chat = properties.getChat();
        validateApiKey(chat.getApiKey(), "mewchat.llm.chat.api-key");

        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(chat.getApiKey())
                .modelName(chat.getModelName())
                .temperature(chat.getTemperature())
                .timeout(chat.getTimeout())
                .logRequests(chat.isLogRequests())
                .build();

        log.info("StreamingChatModel 初始化完成：baseUrl={} model={}",
                chat.getBaseUrl(), chat.getModelName());
        return model;
    }

    /**
     * 向量化模型，RAG 的检索与入库都依赖它。
     *
     * <p>{@code apiKey} 留空时自动复用对话模型的密钥 —— 多数厂商同一个 key 通用，
     * 省去重复配置。若厂商的对话与向量化端点用不同密钥，单独配置即可。
     *
     * @param properties 大模型配置
     * @return 向量化模型实例
     */
    @Bean
    public EmbeddingModel embeddingModel(LlmProperties properties) {
        LlmProperties.Embedding embedding = properties.getEmbedding();

        String apiKey = StringUtils.hasText(embedding.getApiKey())
                ? embedding.getApiKey()
                : properties.getChat().getApiKey();
        String baseUrl = StringUtils.hasText(embedding.getBaseUrl())
                ? embedding.getBaseUrl()
                : properties.getChat().getBaseUrl();
        validateApiKey(apiKey, "mewchat.llm.embedding.api-key（或回退到 mewchat.llm.chat.api-key）");

        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(embedding.getModelName())
                .timeout(embedding.getTimeout())
                .maxRetries(embedding.getMaxRetries())
                .logRequests(embedding.isLogRequests());
        if (embedding.getDimensions() != null) {
            builder.dimensions(embedding.getDimensions());
        }

        log.info("EmbeddingModel 初始化完成：baseUrl={} model={} dimensions={}",
                baseUrl, embedding.getModelName(), embedding.getDimensions());
        return builder.build();
    }

    /**
     * 校验 API 密钥非空，并对"占位值"给出显著告警。
     *
     * <p>LangChain4j 自身也会校验非空，但那是在 Builder 内部抛异常，错误信息里不带
     * 配置项路径。这里提前拦一道，把"该改 yml 的哪一行"直接说清楚。
     *
     * <p><b>为什么占位值只告警、不失败</b>：{@code application.yml} 里给的默认值是
     * {@code sk-REPLACE-WITH-YOUR-REAL-KEY}，为的是让"没有模型凭证"的环境（演示、
     * 纯接口联调）也能启动 —— 每轮对话会在模型调用处失败并走降级（意图识别降级为
     * UNKNOWN、回复走追问/兜底），这正是演示脚本要展示的路径之一。若改成空值即启动失败，
     * 这条路径与一键启动就都没了。
     * 代价是"启动成功"不能证明模型可用，因此这里在启动日志里明确说清，
     * 免得把"应用起来了"误读成"模型接好了"。
     *
     * @param apiKey 待校验的密钥
     * @param configKey 配置项在 yml 中的路径，仅用于错误提示
     */
    private void validateApiKey(String apiKey, String configKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "大模型 API Key 未配置，请在 application.yml 的 " + configKey
                            + " 中填写，或设置环境变量 LLM_API_KEY");
        }
        if (PLACEHOLDER_API_KEY.equals(apiKey.trim())) {
            log.warn("大模型 API Key 仍是占位值（{}）——应用可以启动，但每次模型调用都会失败，"
                    + "对话将走降级路径（追问/兜底）。接真实模型请设置环境变量 LLM_API_KEY",
                    configKey);
        }
    }
}
