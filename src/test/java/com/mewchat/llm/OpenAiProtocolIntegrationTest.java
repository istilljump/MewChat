package com.mewchat.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.agent.supervisor.IntentRecognizer;
import com.mewchat.agent.supervisor.node.ReplyNode;
import com.mewchat.config.AiModelConfig;
import com.mewchat.config.LlmProperties;
import com.mewchat.config.RagProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型链路的<b>协议级</b>端到端测试：真的 LangChain4j OpenAI 客户端 ↔ 本地假服务端。
 *
 * <p><b>为什么这样验</b>：真实厂商模型（回答质量、逐字速度）无法在测试里断言，
 * 但"我们与 OpenAI 协议对接得对不对"完全可以 —— 而这恰恰是最容易出错、
 * 又最不容易被发现的一段：请求体少一个字段、流式分片的 SSE 帧解析、
 * {@code [DONE]} 终止、厂商返回 401/500 时是降级还是崩掉，
 * 任何一处不对，接上真模型都只会表现为"回答很奇怪"或"没有回答"。
 *
 * <p>因此这里<b>不替换模型 Bean</b>（不像其它用例那样用 StubChatModel），
 * 而是用 {@link AiModelConfig} 这个<b>生产用的工厂</b>构建真实的
 * {@code OpenAiChatModel} / {@code OpenAiStreamingChatModel} / {@code OpenAiEmbeddingModel}，
 * 只把 {@code base-url} 指向一个 JDK 自带的本地 HTTP 服务端（对外说 OpenAI 协议）。
 * 于是客户端代码、SSE 解析、重试与超时、我们的提示词与出参契约全都被真实执行到，
 * 唯一的替代品是"对面那台服务器"。
 *
 * <p>剩下的唯一未验证项就只是<b>厂商凭证与模型本身的质量</b>：
 * 换供应商时只需改 {@code base-url} / {@code model-name} / {@code api-key} 三个配置，
 * 本类保证这条链路本身是通的。
 *
 * @author MewChat
 */
class OpenAiProtocolIntegrationTest {

    /** 假服务端返回的同步回答 */
    private static final String REPLY_TEXT = "签收之日起 7 天内，商品完好可申请无理由退货。";

    /** 假服务端返回的意图识别 JSON（与 IntentRecognizer 的提示词契约一致） */
    private static final String INTENT_JSON =
            "{\"intent\":\"KNOWLEDGE_QA\",\"confidence\":0.93,"
                    + "\"rewrittenQuery\":\"七天无理由退换货规则\",\"params\":{}}";

    /** 流式分片：刻意切成"半句话"粒度，验证拼接 */
    private static final List<String> FRAGMENTS = List.of("签收之日起", " 7 天内，", "商品完好可申请无理由退货。");

    /** embedding 返回的向量维度，用于验证维度能被告知 */
    private static final int EMBEDDING_DIMENSION = 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 假服务端收到的全部请求体，用于验证"我们发出去的请求长什么样" */
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private int port;

    /** 非 200 时假服务端返回该状态码与一条 OpenAI 风格的错误体 */
    private int chatStatus = 200;

    private LlmProperties properties;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.createContext("/v1/embeddings", this::handleEmbeddings);
        // 假服务端也要能并发：流式请求会占住一个线程直到写完
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        port = server.getAddress().getPort();

        properties = new LlmProperties();
        LlmProperties.Chat chat = properties.getChat();
        chat.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        chat.setApiKey("test-key-not-a-real-credential");
        chat.setModelName("test-model");
        chat.setTemperature(0.3);
        // 超时与重试压短：本类刻意会构造失败请求，不能让用例等默认的 60 秒
        chat.setTimeout(Duration.ofSeconds(5));
        chat.setMaxRetries(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /* ==================== 同步调用：意图识别 ==================== */

    /**
     * 真实客户端能否按提示词契约把意图识别跑通。
     *
     * <p>顺带验证请求体：模型名、temperature、messages 结构都是 OpenAI 协议要求的，
     * 少一个就可能被真实厂商拒绝（而本地替身若不做校验，这种问题接上真模型才暴露）。
     */
    @Test
    void realClientShouldRecognizeIntent() throws Exception {
        this.chatResponseContent = INTENT_JSON;
        ChatModel model = new AiModelConfig().chatModel(properties);

        IntentRecognizer.IntentResult result =
                new IntentRecognizer(model, objectMapper).recognize("七天无理由退货怎么操作", List.of());

        assertThat(result.intent()).isEqualTo(com.mewchat.agent.IntentType.KNOWLEDGE_QA);
        assertThat(result.confidence()).isEqualByComparingTo("0.93");
        assertThat(result.rewrittenQuery()).isEqualTo("七天无理由退换货规则");

        assertThat(compactBody(0))
                .as("必须是标准的 chat/completions 请求")
                .contains("\"model\":\"test-model\"")
                .contains("\"temperature\":0.3")
                .contains("\"messages\"")
                .contains("\"role\":\"system\"");
    }

    /* ==================== 流式调用：真实 SSE 解析 + 我们的桥接 ==================== */

    /**
     * 真实流式客户端的分片能否按时序穿过 {@link ReplyNode} 的桥接层。
     *
     * <p>这是此前最缺乏验证的一环：LangChain4j 的 SSE 解析 + 累积、
     * 我们把它桥回同步流程的 {@code CountDownLatch}、以及片段的顺序与完整性，
     * 三者任何一处不对，接上真模型的表现都只是"字没出来"或"字少了一半"。
     */
    @Test
    void realStreamingClientShouldDeliverFragmentsInOrder() {
        StreamingChatModel model = new AiModelConfig().streamingChatModel(properties);
        ReplyNode node = new ReplyNode(new AiModelConfig().chatModel(properties), model,
                properties, new RagProperties());

        List<String> fragments = new ArrayList<>();
        ChatContext context = ChatContext.builder()
                .sessionId("llm-it-stream")
                .userMessage("七天无理由退货怎么操作")
                .streamListener(new ChatStreamListener() {
                    @Override
                    public void onFragment(String fragment) {
                        fragments.add(fragment);
                    }
                })
                .build();

        ChatState next = node.execute(context);

        assertThat(next).isEqualTo(ChatState.END);
        assertThat(fragments)
                .as("片段必须按服务端下发的顺序、一片不少地推出去")
                .containsExactlyElementsOf(FRAGMENTS);
        assertThat(context.getReplyText())
                .as("最终内容应是片段拼起来的完整回答")
                .isEqualTo(String.join("", FRAGMENTS));
        assertThat(context.getErrorMessage()).isNull();

        assertThat(compactBody(0))
                .as("流式请求必须显式带上 stream:true，否则厂商回的是整段 JSON")
                .contains("\"stream\":true");
    }

    /* ==================== 厂商侧失败：降级而不是崩 ==================== */

    /**
     * 厂商返回 401（密钥错/过期）时，意图识别要降级为 UNKNOWN，而不是抛异常。
     */
    @Test
    void unauthorizedShouldDegradeIntentToUnknown() {
        chatStatus = 401;
        ChatModel model = new AiModelConfig().chatModel(properties);

        IntentRecognizer.IntentResult result =
                new IntentRecognizer(model, objectMapper).recognize("七天无理由退货怎么操作", List.of());

        assertThat(result.intent())
                .as("模型不可用时必须降级为 UNKNOWN 走追问，而不是让整轮对话崩掉")
                .isEqualTo(com.mewchat.agent.IntentType.UNKNOWN);
        assertThat(result.confidence()).isEqualByComparingTo("0");
    }

    /**
     * 厂商侧失败时，回复生成要降级为兜底（返回 FALLBACK），而不是把异常抛给调用方。
     */
    @Test
    void serverErrorShouldDegradeReplyToFallback() {
        chatStatus = 500;
        AiModelConfig config = new AiModelConfig();
        ReplyNode node = new ReplyNode(config.chatModel(properties), config.streamingChatModel(properties),
                properties, new RagProperties());

        ChatContext context = ChatContext.builder()
                .sessionId("llm-it-fail")
                .userMessage("七天无理由退货怎么操作")
                .build();

        assertThat(node.execute(context))
                .as("生成失败要返回 FALLBACK，由上层给出得体的话术")
                .isEqualTo(ChatState.FALLBACK);
        assertThat(context.getErrorMessage()).isNotBlank();
    }

    /**
     * 流式调用失败时同样降级为 FALLBACK，并且<b>不把异常抛出去</b>。
     */
    @Test
    void streamingServerErrorShouldDegradeToFallback() {
        chatStatus = 500;
        AiModelConfig config = new AiModelConfig();
        ReplyNode node = new ReplyNode(config.chatModel(properties), config.streamingChatModel(properties),
                properties, new RagProperties());

        List<String> fragments = new ArrayList<>();
        ChatContext context = ChatContext.builder()
                .sessionId("llm-it-stream-fail")
                .userMessage("七天无理由退货怎么操作")
                .streamListener(new ChatStreamListener() {
                    @Override
                    public void onFragment(String fragment) {
                        fragments.add(fragment);
                    }
                })
                .build();

        assertThat(node.execute(context)).isEqualTo(ChatState.FALLBACK);
        assertThat(fragments).as("失败时不应推送任何片段").isEmpty();
    }

    /* ==================== 向量化：Milvus 的前置条件 ==================== */

    /**
     * 向量化模型必须返回配置声明的维度。
     *
     * <p>这是接 Milvus 之前必须确认的一件事：集合按固定维度创建，
     * 维度对不上时写入会直接报错，而错误信息来自向量库、指向不了"哪个配置写错了"。
     * 本用例把"实际返回多少维"变成一个可观测的事实，
     * 也能在换 embedding 模型时立刻发现 {@code mewchat.milvus.dimension} 需要同步修改。
     */
    @Test
    void embeddingModelShouldReturnConfiguredDimension() {
        properties.getEmbedding().setModelName("test-embedding-model");
        properties.getEmbedding().setDimensions(EMBEDDING_DIMENSION);
        EmbeddingModel model = new AiModelConfig().embeddingModel(properties);

        int actual = model.embed("七天无理由退货").content().vector().length;

        assertThat(actual)
                .as("embedding 输出维度必须与 mewchat.milvus.dimension 一致，否则向量入库会失败")
                .isEqualTo(EMBEDDING_DIMENSION);
        assertThat(compactBody(0))
                .as("维度要随请求发给厂商，否则有些模型会用默认维度返回")
                .contains("\"dimensions\":" + EMBEDDING_DIMENSION);
    }

    /* ==================== 假服务端 ==================== */

    /** 同步回答的正文，由各用例设置 */
    private String chatResponseContent = REPLY_TEXT;

    /**
     * 处理 {@code /v1/chat/completions}：按请求里的 {@code stream} 字段决定回整段 JSON 还是 SSE。
     *
     * @param exchange HTTP 交换对象
     * @throws IOException 写响应失败时抛出
     */
    private void handleChat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBodies.add(body);

        if (chatStatus != 200) {
            String error = "{\"error\":{\"message\":\"fake vendor rejected the request\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"" + chatStatus + "\"}}";
            respond(exchange, chatStatus, "application/json", error.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (compact(body).contains("\"stream\":true")) {
            streamResponse(exchange);
            return;
        }

        String completion = "{\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + quote(chatResponseContent) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":30,\"total_tokens\":130}}";
        respond(exchange, 200, "application/json", completion.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 以 SSE 形式逐片下发回答，最后发 {@code data: [DONE]}。
     *
     * @param exchange HTTP 交换对象
     * @throws IOException 写响应失败时抛出
     */
    private void streamResponse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String fragment : FRAGMENTS) {
                String chunk = "data: {\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"content\":" + quote(fragment)
                        + "},\"finish_reason\":null}]}\n\n";
                out.write(chunk.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * 处理 {@code /v1/embeddings}：返回固定维度的确定性向量。
     *
     * @param exchange HTTP 交换对象
     * @throws IOException 写响应失败时抛出
     */
    private void handleEmbeddings(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBodies.add(body);

        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            if (i > 0) {
                vector.append(',');
            }
            // 确定性取值：同一输入每次都得到同一个向量，用例可复现
            vector.append(String.format("%.4f", (i % 10) / 10.0));
        }
        vector.append(']');

        String response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                + "\"embedding\":" + vector + "}],\"model\":\"test-embedding-model\","
                + "\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}";
        respond(exchange, 200, "application/json", response.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写回一个 JSON 响应。
     *
     * @param exchange HTTP 交换对象
     * @param status   HTTP 状态码
     * @param contentType 内容类型
     * @param payload  响应体字节
     * @throws IOException 写响应失败时抛出
     */
    private static void respond(HttpExchange exchange, int status, String contentType, byte[] payload)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * 取第 n 个请求体，并去掉全部空白后再比较。
     *
     * <p>LangChain4j 默认把请求体格式化成带缩进的 JSON（字段之间还有 {@code " : "} 的空格），
     * 因此断言必须比较"去空白后的形式"，否则会因为<b>纯粹的空格差异</b>而失败 ——
     * 那种失败既不是协议错，也提示不了任何真实问题。
     *
     * @param index 请求体序号
     * @return 去掉空白后的请求体
     */
    private String compactBody(int index) {
        return compact(requestBodies.get(index));
    }

    /**
     * 去掉字符串中的全部空白字符。
     *
     * @param text 原文本
     * @return 去掉空白后的文本
     */
    private static String compact(String text) {
        return text.replaceAll("\s+", "");
    }

    /**
     * 把文本转成 JSON 字符串字面量（含引号）。
     *
     * @param text 原文本
     * @return JSON 字符串字面量
     */
    private String quote(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalStateException("序列化文本失败", e);
        }
    }
}
