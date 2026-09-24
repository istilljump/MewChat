package com.mewchat.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.config.ObservabilityProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Langfuse 上报客户端的测试。
 *
 * <p><b>用一个真实的本地 HTTP 服务端来验，而不是 Mock 掉 HttpClient</b>：
 * 本类要验证的正是"发出去的请求长什么样" —— 认证头怎么拼、JSON 形状对不对、
 * 路径有没有拼出双斜杠。把 HTTP 层 Mock 掉，这些全都验不到，
 * 测出来只剩"我调用了自己定义的接口"。
 * 用 JDK 自带的 {@code HttpServer} 起一个端口即可，不需要任何额外依赖。
 *
 * <p>另一条主线是<b>"上报失败绝不能影响业务"</b>：服务端返回 4xx、端口根本没人监听，
 * 这些情况都必须被吞掉。可观测性代码最典型的错误就是在这个位置把异常抛回去，
 * 让一次本来成功的对话因为埋点失败而失败。
 *
 * @author MewChat
 */
class LangfuseClientTest {

    private static final String PUBLIC_KEY = "pk-unit-test";

    private static final String SECRET_KEY = "sk-unit-test";

    private static final String SESSION_ID = "session-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    private final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/ingestion", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            receivedAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));

            byte[] payload = "{\"successes\":[],\"errors\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /* ==================== 正常路径 ==================== */

    /**
     * 上报应带上 Basic 认证，payload 里要有 token、耗时、检索命中与工具调用。
     */
    @Test
    void shouldPostTraceWithAuthAndMetadata() throws Exception {
        LangfuseClient client = new LangfuseClient(configuredProperties(serverPort()), objectMapper);

        client.recordTurn(sampleTrace());
        awaitRequests(1);

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                (PUBLIC_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(receivedAuthHeaders).containsExactly(expectedAuth);

        JsonNode batch = objectMapper.readTree(receivedBodies.get(0)).path("batch");
        assertThat(batch.isArray()).isTrue();
        assertThat(batch).hasSize(1);

        JsonNode item = batch.get(0);
        assertThat(item.path("type").asText()).isEqualTo("trace-create");
        assertThat(item.path("id").asText()).isNotBlank();

        JsonNode body = item.path("body");
        assertThat(body.path("sessionId").asText()).isEqualTo(SESSION_ID);
        assertThat(body.path("userId").asText())
                .as("Langfuse 的 userId 是字符串类型")
                .isEqualTo("123456789");
        assertThat(body.path("input").asText()).isEqualTo("我的订单到哪了");
        assertThat(body.path("output").asText()).isEqualTo("您的订单已发货。");
        assertThat(body.path("name").asText()).isEqualTo("chat.turn");

        JsonNode metadata = body.path("metadata");
        assertThat(metadata.path("intent").asText()).isEqualTo("ORDER_QUERY");
        assertThat(metadata.path("toolName").asText()).isEqualTo("order_query");
        assertThat(metadata.path("toolSuccess").asBoolean()).isTrue();
        assertThat(metadata.path("retrievalHits").asInt()).isEqualTo(2);
        assertThat(metadata.path("costMs").asLong()).isEqualTo(1500);
        assertThat(metadata.path("finalState").asText()).isEqualTo("REPLY");
    }

    /**
     * 用户ID为空（游客会话）时不带 userId 字段，而不是塞一个 "null"。
     */
    @Test
    void nullUserIdShouldBeOmitted() throws Exception {
        LangfuseClient client = new LangfuseClient(configuredProperties(serverPort()), objectMapper);

        client.recordTurn(new ChatTurnTrace(SESSION_ID, null, "问题", "回答", "UNKNOWN", "CLARIFY",
                null, null, null, 0, BigDecimal.ZERO, 10L, 0, null));
        awaitRequests(1);

        JsonNode body = objectMapper.readTree(receivedBodies.get(0)).path("batch").get(0).path("body");
        assertThat(body.has("userId")).isFalse();
    }

    /* ==================== 静默与容错 ==================== */

    /**
     * 未配置密钥时一个请求都不该发。
     */
    @Test
    void unconfiguredShouldNotSendAnything() throws InterruptedException {
        ObservabilityProperties properties = configuredProperties(serverPort());
        properties.setPublicKey(null);
        properties.setSecretKey(null);
        LangfuseClient client = new LangfuseClient(properties, objectMapper);

        client.recordTurn(sampleTrace());
        Thread.sleep(300);

        assertThat(receivedBodies).isEmpty();
    }

    /**
     * 显式关闭时同样不发。
     */
    @Test
    void disabledShouldNotSendAnything() throws InterruptedException {
        ObservabilityProperties properties = configuredProperties(serverPort());
        properties.setEnabled(false);
        LangfuseClient client = new LangfuseClient(properties, objectMapper);

        client.recordTurn(sampleTrace());
        Thread.sleep(300);

        assertThat(receivedBodies).isEmpty();
    }

    /**
     * 服务端报错（4xx/5xx）只应记日志，不能把异常抛回业务线程。
     */
    @Test
    void serverErrorShouldBeSwallowed() throws Exception {
        responseStatus = 400;
        LangfuseClient client = new LangfuseClient(configuredProperties(serverPort()), objectMapper);

        client.recordTurn(sampleTrace());
        awaitRequests(1);

        assertThat(receivedBodies).hasSize(1);
    }

    /**
     * 服务端根本连不上时也不能抛异常。
     */
    @Test
    void unreachableHostShouldNotThrow() {
        ObservabilityProperties properties = configuredProperties(1);
        LangfuseClient client = new LangfuseClient(properties, objectMapper);

        // 不抛异常即通过：发送是异步的，失败走 exceptionally 分支只记日志
        client.recordTurn(sampleTrace());
    }

    /**
     * 摘要为 null 时安全跳过（调用方可能拿不到上下文）。
     */
    @Test
    void nullTraceShouldBeIgnored() throws InterruptedException {
        LangfuseClient client = new LangfuseClient(configuredProperties(serverPort()), objectMapper);

        client.recordTurn(null);
        Thread.sleep(200);

        assertThat(receivedBodies).isEmpty();
    }

    /* ==================== 辅助 ==================== */

    /**
     * 轮询等待请求到达（发送是异步的，不能立刻断言）。
     *
     * @param expected 期望的请求数
     * @throws InterruptedException 等待被中断时抛出
     */
    private void awaitRequests(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (receivedBodies.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(receivedBodies)
                .as("超时未收到上报请求")
                .hasSizeGreaterThanOrEqualTo(expected);
    }

    /**
     * 取实际监听的端口。
     *
     * @return 端口
     */
    private int serverPort() {
        return server.getAddress().getPort();
    }

    /**
     * 构造已配置好的可观测性配置。
     *
     * @param port 服务端端口
     * @return 配置对象
     */
    private static ObservabilityProperties configuredProperties(int port) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setEnabled(true);
        properties.setHost("http://127.0.0.1:" + port);
        properties.setPublicKey(PUBLIC_KEY);
        properties.setSecretKey(SECRET_KEY);
        properties.setTimeout(Duration.ofSeconds(3));
        return properties;
    }

    /**
     * 构造一条示例摘要。
     *
     * @return 对话摘要
     */
    private static ChatTurnTrace sampleTrace() {
        return new ChatTurnTrace(
                SESSION_ID,
                123456789L,
                "我的订单到哪了",
                "您的订单已发货。",
                "ORDER_QUERY",
                "REPLY",
                "ToolSpecialist",
                "order_query",
                true,
                2,
                new BigDecimal("0.9000"),
                1500L,
                360,
                "deepseek-chat");
    }
}
