package com.mewchat.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.config.ObservabilityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Langfuse 上报客户端：把每轮对话的摘要发到 Langfuse。
 *
 * <p><b>三条不可动摇的约束</b>（可观测性代码最容易违反的就是第一条）：
 * <ol>
 *     <li><b>绝不影响业务</b>：异步发送、异常全部吞掉只记日志。
 *         上报失败绝不能让一次已经成功的对话变成失败 —— 那是本末倒置</li>
 *     <li><b>未配置时静默跳过</b>：没有密钥就不发请求，只提示一次。
 *         否则没接 Langfuse 的部署会被每分钟几十条连接失败日志刷屏</li>
 *     <li><b>不默认打印内容</b>：payload 里有用户问题与回答，
 *         默认打日志等于默认把用户对话写进日志文件（由 {@code log-payload} 显式开启）</li>
 * </ol>
 *
 * <p><b>为什么用 JDK 自带的 {@link HttpClient} 而不是引入 Langfuse 的 Java SDK</b>：
 * 本项目只需要"往 ingestion 接口 POST 一个 JSON"，为此增加一个依赖（及其版本、
 * 传递依赖、升级节奏）并不划算。SDK 的真正价值在于<b>模型调用级的自动埋点</b> ——
 * 那件事更适合用 LangChain4j 自己的 {@code ChatModelListener} 来做
 * （它能拿到每次模型调用的提示词与返回），而不是靠手工拼 trace。
 * 届时本类应当退化成"只负责发送"的角色。
 *
 * <p><b>已知局限</b>：只上报<b>轮次级</b>trace（一轮对话一条），
 * 没有 generation 级别的明细。因此"意图识别花了多少 token""回复生成花了多久"
 * 这类细分数据看不到 —— 要看到它们需要挂 {@code ChatModelListener}。
 *
 * @author MewChat
 */
@Component
public class LangfuseClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseClient.class);

    /** Langfuse 的批量上报端点 */
    private static final String INGESTION_PATH = "/api/public/ingestion";

    /** trace 名称，在 Langfuse 里按它筛选 */
    private static final String TRACE_NAME = "chat.turn";

    /** 日志里截断响应体的长度，避免一条错误日志刷满屏幕 */
    private static final int MAX_LOGGED_BODY = 500;

    private final ObservabilityProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    /** 是否已经提示过"未配置"，避免每轮对话都提示一次 */
    private final AtomicBoolean unconfiguredNoticed = new AtomicBoolean(false);

    public LangfuseClient(ObservabilityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();

        if (properties.isEnabled() && !properties.isConfigured()) {
            log.info("Langfuse 未配置密钥，可观测上报将跳过；如需启用请配置 "
                    + "mewchat.observability.langfuse.public-key / secret-key");
        }
    }

    /**
     * 上报一轮对话（异步，不阻塞业务线程）。
     *
     * @param trace 本轮对话摘要，不可为 null
     */
    public void recordTurn(ChatTurnTrace trace) {
        if (trace == null) {
            return;
        }
        if (!properties.isConfigured()) {
            if (unconfiguredNoticed.compareAndSet(false, true)) {
                log.debug("跳过 Langfuse 上报（未配置）");
            }
            return;
        }

        String payload;
        try {
            payload = buildPayload(trace);
        } catch (Exception e) {
            // 序列化失败属于契约问题：记 warn 而不是 error，上报永远不该被当成故障
            log.warn("Langfuse 上报内容构造失败，本轮跳过：session={}", trace.sessionId(), e);
            return;
        }

        if (properties.isLogPayload()) {
            log.info("Langfuse 上报内容：{}", payload);
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeHost() + INGESTION_PATH))
                    .timeout(properties.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuthHeader())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            // 比如 host 配成了非法 URL：记下来，但不让业务受影响
            log.warn("Langfuse 上报请求构造失败，本轮跳过：host={}", properties.getHost(), e);
            return;
        }

        long startedAt = System.currentTimeMillis();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        // 把响应体打出来：Langfuse 的 4xx 会在 body 里说明是哪个字段不合法，
                        // 不打出 body 的话排查只能靠猜
                        log.warn("Langfuse 上报被拒绝：status={} body={}",
                                response.statusCode(), abbreviate(response.body()));
                        return;
                    }
                    log.debug("Langfuse 上报成功：session={} 耗时={}ms",
                            trace.sessionId(), System.currentTimeMillis() - startedAt);
                })
                .exceptionally(error -> {
                    log.warn("Langfuse 上报失败（不影响业务）：session={} 原因={}",
                            trace.sessionId(), error.toString());
                    return null;
                });
    }

    /**
     * 构造 Langfuse 批量上报的请求体。
     *
     * <p>形状遵循 Langfuse 的 ingestion 接口：{@code {"batch":[{...,"type":"trace-create"}]}}。
     * 这里只用 trace-create 一种事件，因为拿到的就是轮次级数据（见类注释的局限说明）。
     *
     * @param trace 对话摘要
     * @return JSON 字符串
     * @throws Exception 序列化失败时抛出
     */
    private String buildPayload(ChatTurnTrace trace) throws Exception {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intent", trace.intent());
        metadata.put("finalState", trace.finalState());
        metadata.put("handlerAgent", trace.handlerAgent());
        metadata.put("toolName", trace.toolName());
        metadata.put("toolSuccess", trace.toolSuccess());
        metadata.put("retrievalHits", trace.retrievalHits());
        metadata.put("confidence", trace.confidence());
        metadata.put("costMs", trace.costMs());
        metadata.put("model", trace.modelName());

        Map<String, Object> traceBody = new LinkedHashMap<>();
        // 每次上报用一个新的 traceId：Langfuse 的 trace-create 对同一个 id 是"覆盖"语义，
        // 用固定 id 会把历史轮次互相盖掉。同一会话的关联靠 sessionId 字段，不靠 traceId
        traceBody.put("id", UUID.randomUUID().toString());
        traceBody.put("name", TRACE_NAME);
        traceBody.put("timestamp", timestamp);
        traceBody.put("sessionId", trace.sessionId());
        if (trace.userId() != null) {
            // Langfuse 的 userId 是字符串类型
            traceBody.put("userId", String.valueOf(trace.userId()));
        }
        traceBody.put("input", trace.question());
        traceBody.put("output", trace.answer());
        traceBody.put("metadata", metadata);

        Map<String, Object> batchItem = new LinkedHashMap<>();
        batchItem.put("id", UUID.randomUUID().toString());
        batchItem.put("type", "trace-create");
        batchItem.put("timestamp", timestamp);
        batchItem.put("body", traceBody);

        return objectMapper.writeValueAsString(Map.of("batch", List.of(batchItem)));
    }

    /**
     * 构造 HTTP Basic 认证头。
     *
     * <p>Langfuse 用"公钥作用户名、私钥作密码"的 Basic 认证方式。
     *
     * @return Authorization 头的值
     */
    private String basicAuthHeader() {
        String credentials = properties.getPublicKey() + ":" + properties.getSecretKey();
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 规范化 host（去掉结尾斜杠，避免拼出双斜杠的路径）。
     *
     * @return 规范化后的 host
     */
    private String normalizeHost() {
        String host = properties.getHost().trim();
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    /**
     * 截断过长的响应体。
     *
     * @param body 响应体
     * @return 截断后的文本
     */
    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_LOGGED_BODY ? body : body.substring(0, MAX_LOGGED_BODY) + "…";
    }
}
