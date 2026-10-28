package com.mewchat.rag.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.config.RagProperties;
import com.mewchat.rag.retrieval.RetrievedChunk;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 大模型重排器：让模型对每个候选直接打"相关性分"，替代规则的词面推断。
 *
 * <p><b>与启发式的分工</b>：{@link HeuristicReranker} 用词项覆盖度与标题匹配近似相关性
 * —— 便宜、确定，但"换一种说法就不认识"（"保修"与"质保"零词面重叠）。
 * 本实现把查询与候选一起交给模型，模型按语义判断"这段是否在回答这个问题"，
 * 正是 RRF 与词面规则都做不到的那一步。代价：每次检索多一次模型调用
 * （候选<b>打包成一次调用</b>，不是每候选一次），以及输出稳定性的不确定性 —— 后者由
 * "任何失败都降级"兜住。
 *
 * <p><b>三条不变量（由 {@code LlmRerankerTest} 钉住）</b>：
 * <ol>
 *     <li><b>失败降级等于关闭</b>：模型调用异常、输出不含合法打分 —— 一律返回
 *     "未重排"的结果（保持融合序与融合分、截断到 topN）。开启后的最坏情形，
 *     就是关闭重排时的平凡情形，绝不会更差、绝不抛异常。</li>
 *     <li><b>返回恒为候选子集</b>：不新增、不改写片段内容，只调 {@code score} 与顺序；
 *     分数裁剪到 0~1（它是置信度计算的输入，越界会污染置信度口径）。</li>
 *     <li><b>候选池封顶</b>：只把融合序最前面的 {@code max-candidates} 条送模型
 *     （token 成本护栏），超出部分不参与本轮返回 —— 启发式路径同样只返回 topN，
 *     池外候选本来就不会出现在结果里。</li>
 * </ol>
 *
 * <p><b>分数语义是"绝对相关性"而不是"名次"</b>：刻意不让模型只输出排序 ——
 * 若按名次归一化（第 1 名恒为 1.0），任何查询都会算出满分置信度，
 * "排第一不足以作答"的防线（阶段 12 修标定时明确写下的不变量）就没了。
 * 模型必须给出 0~1 的分数，无关候选就该得低分，置信度闸门才有意义。
 *
 * @author MewChat
 */
public class LlmReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    /**
     * 提示词标记：既是给模型的任务说明，也是假端点/日志里识别"这是重排请求"的依据
     * （其余提示词都不含这四个字，不会误伤其他分支）。
     */
    static final String PROMPT_MARKER = "【重排任务】";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatModel chatModel;
    private final RagProperties properties;

    public LlmReranker(ChatModel chatModel, RagProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topN) {
        if (CollectionUtils.isEmpty(chunks) || topN <= 0) {
            return List.of();
        }

        RagProperties.Rerank config = properties.getRerank();
        List<RetrievedChunk> pool = chunks.size() > config.getMaxCandidates()
                ? new ArrayList<>(chunks.subList(0, config.getMaxCandidates()))
                : chunks;

        double[] scores;
        try {
            // 与 ModelToolCallingService 同款调用形态：同步 ChatRequest + 单条用户消息
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(buildPrompt(query, pool, config.getMaxChunkChars())))
                    .build());
            scores = parseScores(response == null || response.aiMessage() == null
                    ? null : response.aiMessage().text(), pool.size());
        } catch (Exception e) {
            // 模型超时/限流/网络异常：这一次检索退化为"未重排"（不变量 1）。
            // 打 WARN 是为了能从日志发现"LLM 重排实际从未生效"这类静默问题
            log.warn("LLM 重排失败，本次检索退化为融合序（mode=llm 的降级路径）：{}", e.getMessage());
            return truncate(pool, topN);
        }

        if (scores == null) {
            log.warn("LLM 重排输出不可解析，本次检索退化为融合序");
            return truncate(pool, topN);
        }

        // 没有被模型打分的候选记 0.0 并排在最后：模型忽略它 = 模型认为它无关。
        // 若记原始融合分会出现两种量纲混排（相关性分 vs 融合分），排序失去意义
        for (int i = 0; i < pool.size(); i++) {
            pool.get(i).setScore(clamp(scores[i]));
        }
        pool.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());

        log.debug("LLM 重排完成：候选={} 返回={} 最高分={}",
                pool.size(), Math.min(topN, pool.size()), pool.get(0).getScore());
        return truncate(pool, topN);
    }

    /**
     * 构造重排提示词。
     *
     * <p>输出契约用 JSON 数组而不是自由文本：分数要进置信度计算，
     * 必须可解析、可裁剪；"只输出 JSON"这类硬约束加在开头。
     *
     * @param query         用户查询
     * @param pool          待打分候选
     * @param maxChunkChars 单个候选的截断长度
     * @return 提示词
     */
    private String buildPrompt(String query, List<RetrievedChunk> pool, int maxChunkChars) {
        StringBuilder builder = new StringBuilder();
        builder.append(PROMPT_MARKER).append("判断每个知识片段对用户问题的相关性。\n")
                .append("只输出 JSON 数组，不要输出任何其他文字。每个元素形如 {\"i\":片段序号,\"score\":相关性分数}，")
                .append("score 取 0~1：1 表示完全回答了问题，0 表示完全无关。每个片段都必须打分。\n")
                .append("用户问题：").append(query == null ? "" : query.strip()).append('\n');
        for (int i = 0; i < pool.size(); i++) {
            RetrievedChunk chunk = pool.get(i);
            String text = chunk.getText() == null ? "" : chunk.getText();
            if (text.length() > maxChunkChars) {
                text = text.substring(0, maxChunkChars);
            }
            builder.append("片段").append(i + 1).append("（标题：")
                    .append(chunk.getDocTitle() == null ? "无" : chunk.getDocTitle()).append("）：")
                    .append(text.replace('\n', ' ')).append('\n');
        }
        return builder.toString();
    }

    /**
     * 从模型输出解析每个候选的分数。
     *
     * <p>容错顺序：先取首个 '[' 到最后一个 ']' 之间的内容（模型常爱加说明文字或
     * Markdown 代码块），再按 JSON 解析；解析失败或没有任何合法条目返回 null（降级）。
     * 序号越界/重复取第一条；分数裁剪到 0~1；没有出现的候选记 0 分
     * （在 {@link #rerank} 里统一处理，这里以 0 占位）。
     *
     * @param response   模型原始输出
     * @param candidateN 候选数量
     * @return 与候选等长的分数数组；不可解析时返回 null
     */
    private double[] parseScores(String response, int candidateN) {
        if (!StringUtils.hasText(response)) {
            return null;
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            List<?> items = JSON.readValue(response.substring(start, end + 1), List.class);
            double[] scores = new double[candidateN];
            boolean[] seen = new boolean[candidateN];
            boolean any = false;
            for (Object item : items) {
                if (!(item instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                int index = intValue(map.get("i")) - 1;
                if (index < 0 || index >= candidateN || seen[index]) {
                    continue;
                }
                seen[index] = true;
                scores[index] = clamp(doubleValue(map.get("score")));
                any = true;
            }
            return any ? scores : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 未重排的兜底返回：保持传入顺序（融合序），截断到 topN。
     *
     * <p>注意不改写 {@code score}：融合分仍是置信度计算的合法输入
     * （它就是"没有重排器"时 RagService 拿到的值）。
     *
     * @param pool 候选池
     * @param topN 返回条数上限
     * @return 截断后的候选
     */
    private List<RetrievedChunk> truncate(List<RetrievedChunk> pool, int topN) {
        return List.copyOf(pool.size() > topN ? pool.subList(0, topN) : pool);
    }

    private static double clamp(double score) {
        if (Double.isNaN(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
