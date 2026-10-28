package com.mewchat.agent.supervisor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.agent.HistoryTurn;
import com.mewchat.agent.IntentType;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别器：把用户输入解析成"意图 + 置信度 + 改写查询 + 参数"。
 *
 * <p><b>大模型只在这里使用，且只用于"判断"而非"决定流程"</b>。
 * 它给出的意图会被严格校验后交给状态机，流程怎么走由 {@code ChatState} 的转移表说了算。
 *
 * <p><b>稳定性设计</b>：大模型输出天然不可控，因此这里的解析逻辑对
 * "返回 markdown 代码块""意图名大小写不一致""confidence 给成字符串"
 * "干脆返回一段废话" 这几种常见情况都做了容错，
 * 任何一种异常都降级为 {@link IntentType#UNKNOWN} + 置信度 0，
 * 由流程走追问分支，<b>绝不向上抛异常</b>。
 *
 * @author MewChat
 */
@Component
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

    /** 意图识别提示词。候选意图清单由 {@link IntentType#candidates()} 动态生成，保证与枚举同步 */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是电商客服系统的意图识别模块。分析用户最新一句话，输出 JSON 结果。

            可选意图（intent 字段只能取以下之一）：
            %s

            输出格式（只输出 JSON 对象本身，不要解释、不要 markdown 代码块）：
            {
              "intent": "意图名",
              "confidence": 0.85,
              "rewrittenQuery": "补全指代后、适合做知识检索的完整问句",
              "params": {"orderNo": "订单号"}
            }

            判断规则：
            1. 询问退货/换货/退款政策、发票、服务规则等 -> KNOWLEDGE_QA 或 REFUND_ASK
            2. 查询某个订单的状态、金额、内容 -> ORDER_QUERY
            3. 查询包裹到哪了、什么时候到 -> LOGISTICS_QUERY
            4. 询问某件商品的价格、库存、有没有货、什么规格 -> PRODUCT_QUERY
               （与 KNOWLEDGE_QA 的分界：问"是一个确定的值"走 PRODUCT_QUERY，
                 问"是什么规则/怎么用"走 KNOWLEDGE_QA）
            5. 表达不满、要求投诉、要求赔偿 -> COMPLAINT
            6. 完全无法判断用户想做什么 -> UNKNOWN

            字段要求：
            - confidence：你对意图判断的把握，0 到 1 的小数。不确定就给低分，不要一律给 0.9
            - rewrittenQuery：把"这个""那单"等指代补全后的完整问句；无指代时原样返回用户输入
            - params：只放用户明确说出的信息。没有订单号就不要出现 orderNo 这个键。
              订单号通常是 10~20 位的数字串，不要把"我的订单"这种东西当订单号。
              快递/运单号（如 SF1234567890、ZT9876543210）放 trackingNo，不要混进 orderNo。
              用户提到商品或商品类别时，把商品词放 category（如"耳机""连衣裙""生鲜"）；
              问某件商品的价格/库存/规格时，把商品词放 productName（如"无线蓝牙耳机 Pro"）
            """;

    /**
     * 多意图模式的追加指令（拼在 {@link #SYSTEM_PROMPT_TEMPLATE} 之后）。
     *
     * <p>输出结构从"单对象"升级为"对象数组"，单问题也输出单元素数组 ——
     * 两种模式共用同一套字段抽取代码，差别只在最外层。
     * 开头的标记同时是假端点与日志识别"这是多意图请求"的依据。
     */
    static final String MULTI_INTENT_SUFFIX = """

            【多意图模式】用户可能在一句话里问了多件不同的事（如"查一下订单 MC1 到哪了，再看看耳机多少钱"）。
            若确实包含多个独立问题，输出 {"intents":[{...},{...}]}，按用户提到的顺序排列，每个元素的字段与上面完全一致；
            只问了一件事就输出单元素数组。每条元素的 rewrittenQuery 只描述该条问题本身，不要把别的问题混进去。""";

    private final ChatModel chatModel;

    private final ObjectMapper objectMapper;

    public IntentRecognizer(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 识别用户意图。
     *
     * @param userMessage 用户输入（建议传指代消解后的文本）
     * @param history     最近若干轮历史，用于理解省略式提问
     * @return 识别结果，<b>永不为 null</b>；识别失败时返回 UNKNOWN 的降级结果
     */
    public IntentResult recognize(String userMessage, List<HistoryTurn> history) {
        if (!StringUtils.hasText(userMessage)) {
            return IntentResult.unknown(userMessage);
        }

        try {
            String raw = chatModel.chat(buildMessages(userMessage, history, false)).aiMessage().text();
            return parse(raw, userMessage);
        } catch (Exception e) {
            // 模型超时、限流、网络异常都会走到这里：降级为 UNKNOWN，让流程去追问用户
            log.warn("意图识别调用失败，降级为 UNKNOWN：{}", e.getMessage());
            return IntentResult.unknown(userMessage);
        }
    }

    /**
     * 识别用户意图（多意图模式）：一句话里的多个问题拆成有序的子意图列表。
     *
     * <p>仍是<b>一次</b>模型调用 —— 拆解发生在同一次理解里，拆成多次调用
     * 既重复传上下文，也会让"边界在哪"变得摇摆。任何失败都降级为
     * 单条 UNKNOWN 结果：多意图模式的最坏情形等于单意图模式。
     *
     * @param userMessage  用户输入（建议传指代消解后的文本）
     * @param history      最近若干轮历史
     * @param maxIntents   子意图数量上限（受控环的圈数上限），超出部分截断并记日志
     * @return 识别结果列表，<b>永不为空</b>；解析失败时返回单条 UNKNOWN
     */
    public List<IntentResult> recognizeAll(String userMessage, List<HistoryTurn> history, int maxIntents) {
        if (!StringUtils.hasText(userMessage) || maxIntents <= 0) {
            return List.of(IntentResult.unknown(userMessage));
        }

        try {
            String raw = chatModel.chat(buildMessages(userMessage, history, true)).aiMessage().text();
            return parseAll(raw, userMessage, maxIntents);
        } catch (Exception e) {
            log.warn("多意图识别调用失败，降级为单条 UNKNOWN：{}", e.getMessage());
            return List.of(IntentResult.unknown(userMessage));
        }
    }

    /**
     * 构造提示词消息。
     *
     * @param userMessage 用户输入
     * @param history     历史发言
     * @param multi       是否追加多意图指令
     * @return 消息列表
     */
    private List<ChatMessage> buildMessages(String userMessage, List<HistoryTurn> history, boolean multi) {
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, IntentType.candidates());
        if (multi) {
            systemPrompt = systemPrompt + MULTI_INTENT_SUFFIX;
        }

        StringBuilder userPrompt = new StringBuilder();
        if (!CollectionUtils.isEmpty(history)) {
            userPrompt.append("【最近对话】\n");
            for (HistoryTurn turn : history) {
                userPrompt.append(turn.isUser() ? "用户" : "客服").append('：')
                        .append(turn.getContent()).append('\n');
            }
        }
        userPrompt.append("【用户当前输入】\n").append(userMessage);

        return List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt.toString()));
    }

    /**
     * 解析模型返回的 JSON（单意图模式）。
     *
     * @param raw         模型原始输出
     * @param userMessage 用户输入，作为降级时的兜底查询
     * @return 识别结果
     */
    private IntentResult parse(String raw, String userMessage) {
        Map<String, Object> parsed = parseObject(raw, userMessage);
        if (parsed == null) {
            return IntentResult.unknown(userMessage);
        }
        return toResult(parsed, userMessage);
    }

    /**
     * 解析模型返回的 JSON（多意图模式），兼容两种输出形态。
     *
     * <p>规范形态是 {@code {"intents":[...]}}；模型只回了一个单对象
     * （没按多意图指令输出，或问题本来就只有一个）时按单条处理 ——
     * 兼容它比报错好，这本来就是"模型输出不可控"这条线上的一环。
     *
     * @param raw         模型原始输出
     * @param userMessage 用户输入，作为降级时的兜底查询
     * @param maxIntents  子意图上限，超出截断
     * @return 有序的识别结果列表，永不为空
     */
    private List<IntentResult> parseAll(String raw, String userMessage, int maxIntents) {
        Map<String, Object> parsed = parseObject(raw, userMessage);
        if (parsed == null) {
            return List.of(IntentResult.unknown(userMessage));
        }

        if (!(parsed.get("intents") instanceof List<?> items) || items.isEmpty()) {
            return List.of(toResult(parsed, userMessage));
        }

        List<IntentResult> results = new ArrayList<>(Math.min(items.size(), maxIntents));
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object item : items) {
            if (results.size() >= maxIntents) {
                log.warn("子意图数超过上限 {}，已截断（受控环的圈数必须封顶）", maxIntents);
                break;
            }
            if (!(item instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            IntentResult result = toResult((Map<String, Object>) item, userMessage);
            // 去重：模型偶尔会把同一个问题拆成两条几乎相同的输出，
            // 用户会看到同一段话出现两次
            String key = result.intent() + "|" + result.rewrittenQuery() + "|" + result.params();
            if (seen.add(key)) {
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            return List.of(IntentResult.unknown(userMessage));
        }
        return results;
    }

    /**
     * 提取并解析模型输出里的 JSON 对象。
     *
     * @param raw         模型原始输出
     * @param userMessage 用户输入（仅用于日志）
     * @return 解析后的字段表；提取或解析失败返回 null（调用方自行降级）
     */
    private Map<String, Object> parseObject(String raw, String userMessage) {
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("意图识别输出中未找到 JSON 对象，降级为 UNKNOWN：{}", abbreviate(raw));
            return null;
        }

        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("意图识别输出 JSON 解析失败，降级为 UNKNOWN：{}", abbreviate(raw));
            return null;
        }
    }

    /**
     * 把模型输出的单条意图对象转成识别结果。
     *
     * @param parsed      模型输出的字段表
     * @param userMessage 用户输入，作为改写缺失时的兜底查询
     * @return 识别结果
     */
    private IntentResult toResult(Map<String, Object> parsed, String userMessage) {
        IntentType intent = IntentType.parse(asString(parsed.get("intent")));
        BigDecimal confidence = asConfidence(parsed.get("confidence"));
        String rewrittenQuery = StringUtils.hasText(asString(parsed.get("rewrittenQuery")))
                ? asString(parsed.get("rewrittenQuery"))
                : userMessage;
        Map<String, Object> params = asParams(parsed.get("params"));

        log.debug("意图识别结果：intent={} confidence={} params={}", intent, confidence, params.keySet());
        return new IntentResult(intent, confidence, rewrittenQuery, params);
    }

    /**
     * 从模型输出中截取 JSON 对象。
     *
     * <p>模型常常不守"只输出 JSON"的指令，会包上 ```json 代码块或加一句说明。
     * 这里取第一个 <code>{</code> 到最后一个 <code>}</code> 之间的内容，
     * 是容错率最高且不需要正则回溯的做法。
     *
     * @param raw 模型原始输出
     * @return JSON 字符串，找不到返回 null
     */
    private String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    /**
     * 把 JSON 字段安全转成字符串。
     *
     * @param value 原始值
     * @return 字符串，null 安全
     */
    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 把 JSON 字段转成 0~1 的置信度。
     *
     * <p>模型可能返回 0.85（数字）、"0.85"（字符串）、85（百分数），都要能接住，
     * 并统一裁剪到 0~1 区间，避免把越界值带进后续比较。
     *
     * @param value 原始值
     * @return 置信度，无法解析时返回 0
     */
    private BigDecimal asConfidence(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal confidence = new BigDecimal(String.valueOf(value).trim());
            // 模型偶尔会给出 85 这种百分数写法
            if (confidence.compareTo(BigDecimal.ONE) > 0
                    && confidence.compareTo(new BigDecimal("100")) <= 0) {
                confidence = confidence.divide(new BigDecimal("100"));
            }
            if (confidence.compareTo(BigDecimal.ZERO) < 0) {
                return BigDecimal.ZERO;
            }
            if (confidence.compareTo(BigDecimal.ONE) > 0) {
                return BigDecimal.ONE;
            }
            return confidence;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 把 JSON 字段转成参数表。
     *
     * @param value 原始值
     * @return 参数表，非对象或为空时返回空表
     */
    private Map<String, Object> asParams(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Object> params = new HashMap<>(map.size());
        map.forEach((key, item) -> {
            // 过滤掉模型爱塞的占位值，否则"没有订单号"会变成 params 里有个 "null"
            if (key != null && item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text) && !"未知".equals(text)) {
                    params.put(String.valueOf(key), item);
                }
            }
        });
        return params;
    }

    /**
     * 截断过长的模型输出，避免日志被刷屏。
     *
     * @param raw 原始输出
     * @return 至多 200 字符的片段
     */
    private String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "...";
    }

    /**
     * 意图识别结果。
     *
     * @param intent         识别出的意图
     * @param confidence     置信度，0~1
     * @param rewrittenQuery 改写后的查询文本
     * @param params         抽取到的参数
     */
    public record IntentResult(IntentType intent,
                               BigDecimal confidence,
                               String rewrittenQuery,
                               Map<String, Object> params) {

        /**
         * 构造降级结果：意图 UNKNOWN、置信度 0、查询用原文、无参数。
         *
         * @param userMessage 用户原始输入
         * @return 降级结果
         */
        public static IntentResult unknown(String userMessage) {
            return new IntentResult(IntentType.UNKNOWN, BigDecimal.ZERO,
                    userMessage == null ? "" : userMessage, Collections.emptyMap());
        }
    }
}
