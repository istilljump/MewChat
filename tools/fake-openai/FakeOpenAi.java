import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 本地假 OpenAI 端点 —— <b>开发/演示用的替身，不是模型</b>。
 *
 * <p><b>为什么需要它</b>：真实厂商模型需要一个 API Key，而演示、答辩、本地联调
 * 常常不方便（或不应该）拿一个真 Key 去打。本工具用 JDK 自带的
 * {@link HttpServer} 起一个说 OpenAI 协议的服务端，于是
 * <b>应用侧的真实客户端（LangChain4j 的 OpenAiChatModel / OpenAiStreamingChatModel）、
 * 真实的 SSE 流式解析、真实的编排链路全都被执行到</b>，唯一被替换的是"对面那台服务器"。
 *
 * <p><b>它替代不了什么</b>：回答质量、逐字速度、对提示词的遵循程度 ——
 * 这些只有真模型才能体现。演示"产品形态"够用，验证"模型效果"必须换真 Key。
 *
 * <h2>用法</h2>
 * <pre>
 * # 1) 起假端点（默认端口 18124，日志写到当前目录 fake-openai.log）
 * cd tools/fake-openai
 * java FakeOpenAi.java 18124
 *
 * # 2) 把应用指向它（三个环境变量，与接真实厂商的是同一组）
 * export LLM_BASE_URL=http://127.0.0.1:18124/v1
 * export LLM_API_KEY=local-dev-key
 * export LLM_MODEL=fake-model
 *
 * # 3) 换成真实厂商时，把上面三个变量换掉即可，代码与其它配置都不用动
 * export LLM_BASE_URL=https://api.deepseek.com/v1
 * export LLM_API_KEY=sk-xxxx
 * export LLM_MODEL=deepseek-chat
 * </pre>
 *
 * <h2>它会怎么回答</h2>
 * 按提示词里的关键词分流（见 {@link #intent}），刻意覆盖演示需要的几条链路：
 *
 * <table border="1">
 *     <caption>演示问句与预期链路</caption>
 *     <tr><th>问句</th><th>意图</th><th>预期链路</th></tr>
 *     <tr><td>MC202409240001 这单到哪了</td><td>ORDER_QUERY</td><td>→ 订单工具 → 回复</td></tr>
 *     <tr><td>耳机多少钱</td><td>PRODUCT_QUERY</td><td>→ 商品工具（关键词命中）→ 回复</td></tr>
 *     <tr><td>我想问个商品</td><td>PRODUCT_QUERY（不给商品名）</td><td>→ 列候选 → 用户回序号 → 续接 → 商品工具</td></tr>
 *     <tr><td>赠品什么时候发货</td><td>KNOWLEDGE_QA</td><td>→ 知识检索 → 命中带引用 → 回复</td></tr>
 *     <tr><td>发票怎么开</td><td>KNOWLEDGE_QA</td><td>→ 知识检索无召回 → 兜底 + 自动建单</td></tr>
 *     <tr><td>今天天气怎么样</td><td>UNKNOWN</td><td>→ 追问澄清</td></tr>
 * </table>
 *
 * <p>请求体（含完整提示词）会追加写进日志文件，便于核对"发给模型的提示词里
 * 到底有没有业务数据"——演示时这一点比回答本身更有说服力。
 *
 * @author MewChat
 */
public class FakeOpenAi {

    /** 意图识别提示词的标识（与 IntentRecognizer 的模板一致） */
    private static final String INTENT_MARK = "意图识别模块";

    /** 指代消解提示词的标识 */
    private static final String RESOLVE_MARK = "客服对话理解助手";

    /** 用户消息里当前输入的分节标记（与提示词模板一致） */
    /** 指代消解提示词里"最新一句"的分节标记（与提示词模板一致） */
    private static final String LATEST_MARK = "【最新一句】";

    /** 历史对话里用户发言的行首标记 */
    private static final String USER_PREFIX = "用户：";

    private static final String CURRENT_INPUT_MARK = "【用户当前输入】";

    /** 会话摘要提示词的标识 */
    private static final String SUMMARY_MARK = "客服会话摘要助手";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18124;
        Path logFile = Path.of(args.length > 1 ? args[1] : "fake-openai.log");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/chat/completions", exchange -> handle(exchange, logFile));
        // 线程池是必需的：流式请求会占住一个线程直到把分片写完，
        // 单线程会让第二个并发请求一直等着
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("本地假 OpenAI 端点已启动：http://127.0.0.1:" + port + "/v1");
        System.out.println("请求日志：" + logFile.toAbsolutePath());
        System.out.println("把应用指过来：LLM_BASE_URL=http://127.0.0.1:" + port + "/v1");
    }

    private static void handle(HttpExchange exchange, Path logFile) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Files.writeString(logFile, "===== 请求 =====\n" + body + "\n\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // OpenAI 协议用请求体里的 stream 字段区分流式与同步
        if (compact(body).contains("\"stream\":true")) {
            streamReply(exchange, body);
            return;
        }
        json(exchange, intent(body));
    }

    /**
     * 同步调用：模拟意图识别 / 指代消解 / 会话摘要。
     *
     * <p><b>关键词只能匹配"用户那句话"，不能匹配整段请求体</b> —— 这是本工具实现上
     * 最容易踩的坑，而且只有真跑起来才会发现：意图识别的提示词里带着判断规则原文，
     * 里面本身就出现"发票""耳机""价格"这些词，拿整段提示词去匹配的话，
     * <b>每一条提问都会命中同一个分支</b>（实测四类问题全被判成 KNOWLEDGE_QA）。
     * 因此这里先用 {@link #userMessage} 把用户那句话抠出来，再对它做关键词匹配。
     *
     * <p>判断顺序有意义：任务类型靠系统提示词里的独特标识区分（它们不会出现在用户话里），
     * 具体分支靠用户话里的关键词区分。
     *
     * @param body 完整请求体
     * @return 模型应当返回的原始文本
     */
    private static String intent(String body) {
        if (body.contains(RESOLVE_MARK)) {
            // 指代消解要的是"改写后的那一句话"：句中没有指代词时原样返回。
            // 这里必须回用户真正的那句话 —— 回一句占位文本的后果是把用户的整个问题换掉，
            // 而下游（意图识别、检索）拿到的就是这句占位文本
            String message = userMessage(body);
            String current = afterMark(message, LATEST_MARK);
            if (current.isEmpty()) {
                current = lastUserLine(message);
            }
            if (current.isEmpty()) {
                return "指代已消解的完整问句";
            }
            // 本工具不做真正的指代消解，只是把上一句的话题并进来 ——
            // 否则"那这个有时间限制吗"会原样变成检索词，指代对象（退换货）就丢了，
            // 检索必然空手而归。真模型会把它改写成"退换货有时间限制吗"，效果更好；
            // 这里只保证演示里的多轮不会因为指代词而丢掉关键词
            if (containsDemonstrative(current)) {
                String previousTopic = lastUserLine(message);
                if (!previousTopic.isEmpty() && !previousTopic.equals(current)) {
                    return previousTopic + " " + current;
                }
            }
            return current;
        }
        if (body.contains(SUMMARY_MARK)) {
            // 摘要同样要纯文本
            return "用户咨询了商品价格与订单物流，均已给出结论，无未解决事项。";
        }
        if (!body.contains(INTENT_MARK)) {
            return "{}";
        }

        // 只在"用户当前输入"里匹配关键词：意图识别的提示词里带着【最近对话】，
        // 历史里出现过"耳机""价格"等词，拿整段去匹配会让后续每一轮都命中同一个分支
        String message = currentInput(body);
        if (message.isEmpty()) {
            message = userMessage(body);
        }
        if (message.contains("想问个商品")) {
            // 刻意不给商品名：用来演示"列候选 → 用户回序号 → 续接"
            return json("PRODUCT_QUERY", "0.90", "想咨询商品信息", "");
        }
        if (message.contains("天气")) {
            return json("UNKNOWN", "0.20", "今天天气怎么样", "");
        }
        if (message.contains("发票")) {
            // 知识库里没有这个主题 → 用来演示兜底 + 自动建单
            return json("KNOWLEDGE_QA", "0.95", "发票怎么开", "");
        }
        if (message.contains("时间限制") || message.contains("时限")) {
            // 多轮演示里的追问："那这个有时间限制吗"
            return json("KNOWLEDGE_QA", "0.90", "退换货是否有时间限制", "");
        }
        if (message.contains("退货") || message.contains("换货") || message.contains("退款")) {
            // 项目里到处在用的示例问句："七天无理由退货怎么操作"
            return json("KNOWLEDGE_QA", "0.95", "七天无理由退换货规则", "");
        }
        if (message.contains("赠品")) {
            return json("KNOWLEDGE_QA", "0.95", "赠品什么时候发货", "");
        }
        if (message.contains("耳机") || message.contains("键盘") || message.contains("保温杯")
                || message.contains("多少钱") || message.contains("价格") || message.contains("有货")) {
            String product = message.contains("保温杯") ? "保温杯" : "耳机";
            return json("PRODUCT_QUERY", "0.95", "查询商品价格与库存",
                    ",\"productName\":\"" + product + "\"");
        }
        // 默认当订单查询：用户话里带了订单号就一并给出
        String orderNo = message.contains("MC202409240001") ? "MC202409240001" : "";
        return json("ORDER_QUERY", "0.94", "查询订单状态与物流",
                orderNo.isEmpty() ? "" : ",\"orderNo\":\"" + orderNo + "\"");
    }

    /**
     * 取用户消息里最后一条「用户：」后面的内容。
     *
     * <p>指代消解的提示词只有【最近对话】一个分节，用户当前那句话就是里面最后一条
     * "用户："。用它来还原"用户刚说了什么"。
     *
     * @param message 用户消息
     * @return 最后一条用户发言；找不到时返回空串
     */
    private static String lastUserLine(String message) {
        String[] lines = message.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith(USER_PREFIX)) {
                return line.substring(USER_PREFIX.length()).trim();
            }
        }
        return "";
    }

    /**
     * 判断一句话里是否含指代词。
     *
     * @param text 用户发言
     * @return true 表示含指代词
     */
    private static boolean containsDemonstrative(String text) {
        return text.contains("这个") || text.contains("那个") || text.contains("它")
                || text.contains("那单") || text.contains("这笔");
    }

    /**
     * 取分节标记之后的那一段文本。
     *
     * <p>两个提示词模板的分节名不同：意图识别用【用户当前输入】、指代消解用【最新一句】，
     * 内容都是用户刚说的那句话。
     *
     * @param message 用户消息
     * @param mark    分节标记
     * @return 标记之后的内容；没有该标记时返回空串
     */
    private static String afterMark(String message, String mark) {
        int index = message.indexOf(mark);
        return index < 0 ? "" : message.substring(index + mark.length()).trim();
    }

    /**
     * 从用户消息里抠出"当前这一句"。
     *
     * <p>意图识别与指代消解的提示词都把历史拼在同一段里，形如：
     * <pre>
     * 【最近对话】
     * 用户：…
     * 客服：…
     * 【用户当前输入】
     * 用户真正问的这一句
     * </pre>
     * <b>关键词必须只匹配"【用户当前输入】"之后的部分</b>：历史里出现过"耳机""价格"
     * 这类词，拿整段去匹配会让后面每一轮都被判成第一个命中的分支（实测踩过）。
     *
     * @param body 完整请求体
     * @return 当前输入；没有这个标记时返回空串
     */
    private static String currentInput(String body) {
        return afterMark(userMessage(body), CURRENT_INPUT_MARK);
    }

    /**
     * 从请求体里抠出用户消息。
     *
     * <p>请求体的 {@code messages} 是"系统提示词 + 用户消息"，两者在 JSON 里是并列的两项，
     * 因此取<b>最后一个</b> {@code content} 值就是用户那句话（意图识别只有一条用户消息）。
     * 不引入 JSON 库是有意的：本工具只依赖 JDK，随手就能起。
     *
     * <p>解析失败时退回整段请求体：宁可多匹配到关键词，也不要因为解析问题让工具直接不可用。
     *
     * @param body 完整请求体
     * @return 用户消息文本
     */
    private static String userMessage(String body) {
        int keyIndex = body.lastIndexOf("\"content\"");
        if (keyIndex < 0) {
            return body;
        }
        int quote = body.indexOf('"', body.indexOf(':', keyIndex) + 1);
        if (quote < 0) {
            return body;
        }
        StringBuilder text = new StringBuilder();
        for (int i = quote + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\') {
                // 转义序列要还原成真正的字符：JSON 里的换行是 "\n" 两个字符，
                // 若原样收下字母 n，整段提示词就会变成一行 ——
                // 而下面要按行取"用户："「最新一句」，一行时什么都取不到（实测踩过）
                char escaped = body.charAt(++i);
                switch (escaped) {
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    default -> text.append(escaped);   // \" \\ \/ 等原样收下
                }
                continue;
            }
            if (c == '"') {
                break;
            }
            text.append(c);
        }
        return text.length() == 0 ? body : text.toString();
    }

    /**
     * 拼一条意图识别结果 JSON。
     *
     * @param intentType 意图名
     * @param confidence 置信度
     * @param rewritten  改写后的查询
     * @param extraParams 额外参数片段（形如 {@code ,"orderNo":"MC..."}），可为空
     * @return JSON 文本
     */
    private static String json(String intentType, String confidence, String rewritten, String extraParams) {
        return "{\"intent\":\"" + intentType + "\",\"confidence\":" + confidence
                + ",\"rewrittenQuery\":\"" + rewritten + "\",\"params\":{" + strip(extraParams) + "}}";
    }

    private static String strip(String params) {
        return params != null && params.startsWith(",") ? params.substring(1) : (params == null ? "" : params);
    }

    /**
     * 流式调用：分片下发回答。
     *
     * <p>回答内容刻意与提示词里的业务数据挂钩：只有提示词里带着工具查到的事实
     * （价格、库存、承运商），才会说出对应的数字。演示时这一点就是
     * "业务数据真的进了提示词"的直接证据。
     *
     * @param exchange HTTP 交换对象
     * @param prompt   完整提示词
     * @throws IOException 写响应失败时抛出
     */
    private static void streamReply(HttpExchange exchange, String prompt) throws IOException {
        List<String> fragments = fragmentsFor(prompt);

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String fragment : fragments) {
                out.write(chunk(fragment).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * 按提示词里出现的事实决定回答分片。
     *
     * @param prompt 完整提示词
     * @return 分片列表
     */
    private static List<String> fragmentsFor(String prompt) {
        if (prompt.contains("499.00")) {
            return List.of("无线蓝牙耳机 Pro", "售价 499 元，", "库存 128 件，现货充足。");
        }
        if (prompt.contains("258.00")) {
            return List.of("智能保温杯 500ml", "售价 258 元，", "库存 75 件，现货充足。");
        }
        if (prompt.contains("赠送品") || prompt.contains("赠品")) {
            return List.of("赠品随主商品一起发出。", "若赠品缺货，", "会在到货后 3 个工作日内单独寄出。");
        }
        if (prompt.contains("顺丰速运") || prompt.contains("运单号")) {
            return List.of("您的订单已由顺丰速运发出，", "最新轨迹：", "杭州西湖集散中心，预计明天送达。");
        }
        return List.of("您好，", "我按您提供的信息查了一下，", "以下是查询结果。");
    }

    /**
     * 拼一个 SSE 数据帧（OpenAI 流式响应格式）。
     *
     * @param content 分片内容
     * @return SSE 帧文本
     */
    private static String chunk(String content) {
        return "data: {\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"content\":\"" + content + "\"},\"finish_reason\":null}]}\n\n";
    }

    /**
     * 回一个整段 JSON（非流式响应）。
     *
     * @param exchange HTTP 交换对象
     * @param payload  模型返回的原始文本
     * @throws IOException 写响应失败时抛出
     */
    private static void json(HttpExchange exchange, String payload) throws IOException {
        String completion = "{\"id\":\"chatcmpl-fake\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + escape(payload) + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":30,\"total_tokens\":130}}";
        byte[] bytes = completion.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
