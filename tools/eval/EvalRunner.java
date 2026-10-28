// ============================================================================
// MewChat 回答质量评测执行器（单文件，仅依赖 JDK）
//
// 用途：对运行中的应用走真实 HTTP + SSE（登录 → 建会话 → 逐轮 send → 清理会话），
//       按 tools/eval/cases.json 逐条断言，输出通过率报告。
//       用法与断言口径见 docs/eval-guide.md。
//
// 运行（先编译再运行，不要用单文件源码启动——原因见 tools/fake-openai/run.bat 头注释）：
//   javac -encoding UTF-8 -d build EvalRunner.java
//   java -cp build EvalRunner
//
// 环境变量（均可选）：
//   MEWCHAT_EVAL_BASE_URL   应用地址，默认 http://127.0.0.1:8080
//   MEWCHAT_EVAL_USERNAME   评测账号，默认 alice
//   MEWCHAT_EVAL_PASSWORD   评测口令，默认 123456
//   MEWCHAT_EVAL_CASES      用例文件路径，默认与本类同目录的 cases.json
//
// 控制台输出只含 ASCII（Windows 控制台常见 GBK，中文会乱码）；
// 完整的 UTF-8 报告写入 tools/eval/report.txt。
// ============================================================================

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvalRunner {

    private static final String BASE_URL = env("MEWCHAT_EVAL_BASE_URL", "http://127.0.0.1:8080");
    private static final String USERNAME = env("MEWCHAT_EVAL_USERNAME", "alice");
    private static final String PASSWORD = env("MEWCHAT_EVAL_PASSWORD", "123456");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    /** 一条断言失败/通过记录 */
    record Assertion(String item, boolean passed, String detail) { }

    /** 一轮对话的执行结果 */
    record TurnResult(String message, String reply, Map<String, Object> done,
                      List<Assertion> assertions, boolean passed, String failure) { }

    /** 一条用例的执行结果 */
    record CaseResult(String id, String name, List<TurnResult> turns, boolean passed) { }

    public static void main(String[] args) throws Exception {
        Path casesPath = args.length > 0 ? Path.of(args[0])
                : Path.of(env("MEWCHAT_EVAL_CASES", "cases.json"));
        if (!Files.exists(casesPath)) {
            // 容许从仓库根目录执行：tools/eval/cases.json
            Path fallback = Path.of("tools", "eval", "cases.json");
            if (Files.exists(fallback)) {
                casesPath = fallback;
            } else {
                System.out.println("[X] cases file not found: " + casesPath.toAbsolutePath());
                System.exit(2);
                return;
            }
        }

        System.out.println("MewChat Eval Runner");
        System.out.println("  base url : " + BASE_URL);
        System.out.println("  username : " + USERNAME);
        System.out.println("  cases    : " + casesPath.toAbsolutePath());

        // 先解析用例再连应用：用例文件有问题时立刻失败，而不是先跑一半再挂在别处
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(
                Files.readString(casesPath, StandardCharsets.UTF_8));
        List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
        if (cases == null || cases.isEmpty()) {
            System.out.println("[X] no cases found in " + casesPath);
            System.exit(2);
            return;
        }
        System.out.println("  loaded   : " + cases.size() + " cases");
        System.out.println();

        String token;
        try {
            token = login();
        } catch (java.net.ConnectException e) {
            System.out.println("[X] cannot reach app at " + BASE_URL + " — is it running?");
            System.exit(2);
            return;
        }

        List<CaseResult> results = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String id = String.valueOf(c.get("id"));
            String name = String.valueOf(c.get("name"));
            List<Map<String, Object>> turns = (List<Map<String, Object>>) c.get("turns");
            System.out.print("  [" + id + "] ");
            CaseResult r = runCase(token, id, name, turns);
            results.add(r);
            System.out.println(r.passed() ? "PASS" : "FAIL");
            for (TurnResult t : r.turns()) {
                if (t.failure() != null) {
                    System.out.println("        turn failed: " + t.failure());
                }
            }
        }

        writeReport(results, casesPath);
        long pass = results.stream().filter(CaseResult::passed).count();
        System.out.println();
        System.out.println("Summary: " + pass + "/" + results.size() + " cases passed ("
                + (results.size() == 0 ? 0 : Math.round(pass * 1000.0 / results.size()) / 10.0) + "%)");
        System.out.println("Full report (UTF-8): tools/eval/report.txt");
        System.exit(pass == results.size() ? 0 : 1);
    }

    // ------------------------------------------------------------------ 执行

    /** 每条用例一个全新会话：避免多轮记忆跨用例串味，也保证断言只取决于本用例的轮次。 */
    private static CaseResult runCase(String token, String id, String name,
                                      List<Map<String, Object>> turns) {
        String sessionId = null;
        try {
            sessionId = createSession(token);
        } catch (Exception e) {
            TurnResult t = new TurnResult(null, null, null, List.of(), false,
                    "create session failed: " + e.getMessage());
            return new CaseResult(id, name, List.of(t), false);
        }

        List<TurnResult> turnResults = new ArrayList<>();
        try {
            for (Map<String, Object> turn : turns) {
                String message = String.valueOf(turn.get("message"));
                Map<String, Object> expect = (Map<String, Object>) turn.get("expect");
                turnResults.add(runTurn(token, sessionId, message, expect));
            }
        } finally {
            // 清理会话（会话逻辑删除、消息物理删除），让评测可以反复执行且不留脏数据。
            // 注意：兜底用例会留下工单与低置信度问题——那是业务行为，不是评测残留。
            try {
                delete("/api/chat/session/" + sessionId, token);
            } catch (Exception ignore) {
                // 清理失败不影响评测结论
            }
        }

        boolean allTurnsPassed = turnResults.stream().allMatch(TurnResult::passed);
        return new CaseResult(id, name, turnResults, allTurnsPassed);
    }

    private static TurnResult runTurn(String token, String sessionId, String message,
                                      Map<String, Object> expect) {
        SseTurn turn;
        try {
            turn = sendAndCollect(token, sessionId, message);
        } catch (Exception e) {
            return new TurnResult(message, null, null, List.of(), false,
                    "send failed: " + e.getMessage());
        }
        if (turn.error != null) {
            // 对话限流（mewchat.rate-limit.chat-requests-per-minute）会把连续的评测轮次拒掉：
            // 评测是一次连发 20 轮的自动化调用，天然超过"每用户每分钟 10 轮"的人速阈值。
            // 给出明确指引而不是让后面的用例集体莫名失败
            if (turn.error.contains("20003")) {
                return new TurnResult(message, turn.replyText.toString(), null, List.of(), false,
                        "sse error event: " + turn.error
                        + " —— 触发了对话限流：请以 MEWCHAT_RATELIMIT_ENABLED=false 重启应用后重跑评测");
            }
            return new TurnResult(message, turn.replyText.toString(), null, List.of(), false,
                    "sse error event: " + turn.error);
        }
        if (turn.done == null) {
            return new TurnResult(message, turn.replyText.toString(), null, List.of(), false,
                    "stream ended without done event");
        }

        List<Assertion> assertions = evaluate(expect, turn);
        boolean passed = assertions.stream().allMatch(Assertion::passed);
        String failure = assertions.stream().filter(a -> !a.passed())
                .map(a -> a.item() + ": " + a.detail())
                .reduce((a, b) -> a + "; " + b).orElse(null);
        return new TurnResult(message, turn.replyText.toString(), turn.done, assertions, passed, failure);
    }

    /** 按用例的 expect 字段逐项断言。任何字段缺省 = 不检查。 */
    private static List<Assertion> evaluate(Map<String, Object> expect, SseTurn turn) {
        List<Assertion> out = new ArrayList<>();
        Map<String, Object> reply = (Map<String, Object>) turn.done.get("data");
        if (reply == null) {
            out.add(new Assertion("done.data", false, "done event has no data payload"));
            return out;
        }
        String finalState = str(reply.get("finalState"));
        String intent = str(reply.get("intent"));
        String content = normalize(turn.replyText.toString());
        boolean handoff = Boolean.TRUE.equals(reply.get("handoffRequired"));

        checkAnyOf(out, "intent", intent, expect.get("intent"), expect.get("intentAny"));
        checkAnyOf(out, "finalState", finalState, expect.get("finalState"), expect.get("finalStateAny"));

        if (expect.containsKey("mustContain")) {
            List<Object> words = (List<Object>) expect.get("mustContain");
            for (Object w : words) {
                String needle = normalize(String.valueOf(w));
                out.add(new Assertion("mustContain[" + w + "]", content.contains(needle),
                        content.contains(needle) ? "" : "reply does not contain it"));
            }
        }
        if (expect.containsKey("mustContainAny")) {
            List<Object> words = (List<Object>) expect.get("mustContainAny");
            boolean any = words.stream().map(w -> content.contains(normalize(String.valueOf(w))))
                    .reduce(false, Boolean::logicalOr);
            out.add(new Assertion("mustContainAny" + words, any, any ? "" : "reply contains none of them"));
        }
        if (expect.containsKey("citationTitle")) {
            String needle = normalize(String.valueOf(expect.get("citationTitle")));
            List<Object> citations = (List<Object>) reply.get("citations");
            boolean any = citations != null && citations.stream().anyMatch(c -> {
                Object title = ((Map<?, ?>) c).get("docTitle");
                return title != null && normalize(String.valueOf(title)).contains(needle);
            });
            out.add(new Assertion("citationTitle~" + expect.get("citationTitle"), any,
                    any ? "" : "no citation whose docTitle matches"));
        }
        if (expect.containsKey("handoffRequired")) {
            boolean want = Boolean.parseBoolean(String.valueOf(expect.get("handoffRequired")));
            out.add(new Assertion("handoffRequired=" + want, handoff == want,
                    "actual=" + handoff));
        }
        return out;
    }

    private static void checkAnyOf(List<Assertion> out, String item, String actual,
                                   Object exact, Object anyOf) {
        if (exact != null) {
            out.add(new Assertion(item + "=" + exact, exact.equals(actual),
                    "actual=" + actual));
        } else if (anyOf instanceof List<?> list && !list.isEmpty()) {
            out.add(new Assertion(item + " in " + list, list.contains(actual),
                    "actual=" + actual));
        }
    }

    /** 断言口径统一去空白：模型措辞里的空格/换行不参与匹配（"3 个工作日" == "3个工作日"）。 */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    // ------------------------------------------------------------------ HTTP

    private static String login() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", USERNAME);
        body.put("password", PASSWORD);
        Map<String, Object> resp = postJson("/api/auth/login", body, null);
        requireSuccess(resp, "login");
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        String token = data == null ? null : str(data.get("token"));
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("login response has no token (is sql/06_demo_seed.sql applied?)");
        }
        return token;
    }

    private static String createSession(String token) throws Exception {
        Map<String, Object> resp = postJson("/api/chat/session", Map.of(), token);
        requireSuccess(resp, "create session");
        return str(resp.get("data"));
    }

    private static Map<String, Object> postJson(String path, Object body, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.write(body), StandardCharsets.UTF_8));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 401) {
            throw new IllegalStateException("401 unauthorized (token missing/expired) on " + path);
        }
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(resp.body());
        if (parsed == null) {
            throw new IllegalStateException("non-JSON response (HTTP " + resp.statusCode() + ") on " + path);
        }
        return parsed;
    }

    private static void delete(String path, String token) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + token)
                .DELETE().build();
        HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 发送一轮对话并收完整个 SSE 事件流。
     *
     * <p>显式等 done/error 才算一轮结束：只读到 EOF 不够 —— 未来若引入"断开不结束流程"的
     * 行为，EOF 判定会提前放行半截结果。done 事件是后端声明的权威结束信号。
     */
    private static SseTurn sendAndCollect(String token, String sessionId, String message)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("message", message);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/chat/send"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token)
                .timeout(java.time.Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(MiniJson.write(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.util.stream.Stream<String>> resp =
                HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("send returned HTTP " + resp.statusCode());
        }

        SseTurn turn = new SseTurn();
        StringBuilder dataBuffer = new StringBuilder();
        String event = null;
        try (java.util.stream.Stream<String> lines = resp.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring("data:".length()).trim());
                } else if (line.isEmpty() && event != null && dataBuffer.length() > 0) {
                    // 空行 = 一帧结束，解析这一帧
                    String payload = dataBuffer.toString();
                    Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(payload);
                    switch (event) {
                        case "message" -> {
                            if (parsed != null && parsed.get("data") != null) {
                                turn.replyText.append(str(parsed.get("data")));
                            }
                        }
                        case "done" -> turn.done = parsed;
                        case "error" -> turn.error = payload;
                        default -> { /* session/state 事件不参与断言 */ }
                    }
                    event = null;
                    dataBuffer.setLength(0);
                    if (turn.done != null || turn.error != null) {
                        break;
                    }
                }
            }
        }
        return turn;
    }

    /** 一轮 SSE 的收集结果 */
    private static class SseTurn {
        final StringBuilder replyText = new StringBuilder();
        Map<String, Object> done;
        String error;
    }

    private static void requireSuccess(Map<String, Object> resp, String what) {
        Object code = resp.get("code");
        if (!"0".equals(String.valueOf(code))) {
            throw new IllegalStateException(what + " failed: code=" + code + " message=" + resp.get("message"));
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    // ------------------------------------------------------------------ 报告

    private static void writeReport(List<CaseResult> results, Path casesPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("MewChat 评测报告\n");
        sb.append("生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("应用地址：").append(BASE_URL).append('\n');
        sb.append("评测账号：").append(USERNAME).append('\n');
        sb.append("用例文件：").append(casesPath.toAbsolutePath()).append("\n\n");

        long pass = results.stream().filter(CaseResult::passed).count();
        sb.append("通过 ").append(pass).append('/').append(results.size()).append(" 条用例\n");
        sb.append("=".repeat(60)).append("\n\n");

        for (CaseResult r : results) {
            sb.append('[').append(r.passed() ? "PASS" : "FAIL").append("] ")
              .append(r.id()).append(' ').append(r.name()).append('\n');
            for (TurnResult t : r.turns()) {
                sb.append("  问：").append(t.message()).append('\n');
                String reply = t.reply() == null ? "" : t.reply();
                sb.append("  答：").append(reply.length() > 200 ? reply.substring(0, 200) + "…" : reply).append('\n');
                if (t.done() != null) {
                    Map<String, Object> data = (Map<String, Object>) t.done().get("data");
                    if (data != null) {
                        sb.append("  意图=").append(data.get("intent"))
                          .append(" 终态=").append(data.get("finalState"))
                          .append(" 置信度=").append(data.get("confidence"))
                          .append(" 转人工=").append(data.get("handoffRequired")).append('\n');
                    }
                }
                for (Assertion a : t.assertions()) {
                    sb.append("  ").append(a.passed() ? "  ok " : "  XX ").append(a.item());
                    if (!a.passed()) {
                        sb.append("  —— ").append(a.detail());
                    }
                    sb.append('\n');
                }
                if (t.failure() != null) {
                    sb.append("  失败：").append(t.failure()).append('\n');
                }
                sb.append('\n');
            }
        }
        Files.writeString(Path.of("report.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 迷你 JSON

    /**
     * 极简 JSON 解析/写出（对象=LinkedHashMap、数组=ArrayList、字符串、数值=Double、布尔、null）。
     *
     * <p>评测器只需要"取出字段"，不需要完整规范实现：字符串内的转义与数值/字面量
     * 按最常见的形态处理；解析失败抛 {@link IllegalStateException}，调用方按失败记录。
     */
    static final class MiniJson {

        static Object parse(String text) {
            P p = new P(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i < p.s.length()) {
                throw new IllegalStateException("trailing content at " + p.i);
            }
            return v;
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            writeValue(sb, o);
            return sb.toString();
        }

        private static void writeValue(StringBuilder sb, Object o) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String s) {
                writeString(sb, s);
            } else if (o instanceof Boolean || o instanceof Number) {
                sb.append(o);
            } else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeValue(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof List<?> l) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    writeValue(sb, l.get(i));
                }
                sb.append(']');
            } else {
                writeString(sb, String.valueOf(o));
            }
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        private static final class P {
            final String s;
            int i;

            P(String s) {
                this.s = s;
            }

            void ws() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            Object value() {
                if (i >= s.length()) {
                    throw new IllegalStateException("unexpected end of JSON");
                }
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> { expect("true"); yield Boolean.TRUE; }
                    case 'f' -> { expect("false"); yield Boolean.FALSE; }
                    case 'n' -> { expect("null"); yield null; }
                    default -> number();
                };
            }

            Map<String, Object> object() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; // {
                ws();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String key = string();
                    ws();
                    if (s.charAt(i) != ':') {
                        throw new IllegalStateException("expected ':' at " + i);
                    }
                    i++;
                    ws();
                    m.put(key, value());
                    ws();
                    char c = s.charAt(i);
                    i++;
                    if (c == '}') {
                        return m;
                    }
                    if (c != ',') {
                        throw new IllegalStateException("expected ',' or '}' at " + (i - 1));
                    }
                }
            }

            List<Object> array() {
                List<Object> l = new ArrayList<>();
                i++; // [
                ws();
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    char c = s.charAt(i);
                    i++;
                    if (c == ']') {
                        return l;
                    }
                    if (c != ',') {
                        throw new IllegalStateException("expected ',' or ']' at " + (i - 1));
                    }
                }
            }

            String string() {
                if (s.charAt(i) != '"') {
                    throw new IllegalStateException("expected string at " + i);
                }
                i++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> throw new IllegalStateException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
            }

            Object number() {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                String n = s.substring(start, i);
                try {
                    long l = Long.parseLong(n);
                    if (Math.abs(l) < 1_000_000_000L) {
                        // 保守起见：小数值用 Long 保留（如 code），其余用 Double
                        return l;
                    }
                    return Double.parseDouble(n);
                } catch (NumberFormatException ignore) {
                    return Double.parseDouble(n);
                }
            }

            void expect(String word) {
                if (!s.startsWith(word, i)) {
                    throw new IllegalStateException("expected " + word + " at " + i);
                }
                i += word.length();
            }
        }
    }
}
