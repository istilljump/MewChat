package com.mewchat.rag.rerank;

import com.mewchat.config.RagProperties;
import com.mewchat.rag.retrieval.RetrievedChunk;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 重排器的单元测试（不依赖 Spring 与网络）。
 *
 * <p>重点钉 {@link LlmReranker} 类注释里写明的三条不变量：
 * <b>失败降级等于关闭</b>（模型异常/输出不可解析时返回融合序，分数原样）、
 * <b>返回恒为候选子集</b>（不新增片段、分数裁剪 0~1）、
 * <b>候选池封顶</b>（超出 max-candidates 的候选不进提示词也不返回）。
 * 另有一条重排器共同的底线：分数是"绝对相关性"而非"名次" ——
 * 模型给无关候选打低分时，它必须沉底，置信度闸门才不会被"排第一就是满分"绕过。
 *
 * @author MewChat
 */
class LlmRerankerTest {

    private final RagProperties properties = new RagProperties();

    /**
     * 按模型分数重排：结果顺序与分数都来自模型，无关候选沉底。
     */
    @Test
    void shouldReorderByModelScores() {
        RetrievedChunk a = chunk("a", 0.50);
        RetrievedChunk b = chunk("b", 0.60);
        RetrievedChunk c = chunk("c", 0.90);
        LlmReranker reranker = new LlmReranker(
                modelResponding("[{\"i\":3,\"score\":0.95},{\"i\":1,\"score\":0.80},{\"i\":2,\"score\":0.10}]"),
                properties);

        List<RetrievedChunk> result = reranker.rerank("耳机多少钱", new ArrayList<>(List.of(a, b, c)), 5);

        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .containsExactly("c", "a", "b");
        assertThat(result.get(0).getScore()).isEqualTo(0.95);
        assertThat(result.get(1).getScore()).isEqualTo(0.80);
        assertThat(result.get(2).getScore()).isEqualTo(0.10);
    }

    /**
     * 模型抛异常：返回融合序、分数原样 —— "失败降级等于关闭"。
     */
    @Test
    void shouldFallBackToFusionOrderWhenModelThrows() {
        RetrievedChunk a = chunk("a", 0.50);
        RetrievedChunk b = chunk("b", 0.60);
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("模拟模型超时");
            }
        };

        List<RetrievedChunk> result = new LlmReranker(broken, properties)
                .rerank("耳机", new ArrayList<>(List.of(a, b)), 5);

        assertThat(result).extracting(RetrievedChunk::getChunkId).containsExactly("a", "b");
        assertThat(result.get(0).getScore()).isEqualTo(0.50);
        assertThat(result.get(1).getScore()).isEqualTo(0.60);
    }

    /**
     * 输出不可解析（没有 JSON 数组）：同样返回融合序。
     */
    @Test
    void shouldFallBackWhenOutputUnparseable() {
        RetrievedChunk a = chunk("a", 0.50);
        RetrievedChunk b = chunk("b", 0.60);
        LlmReranker reranker = new LlmReranker(modelResponding("抱歉，我无法判断这两段的相关性。"), properties);

        List<RetrievedChunk> result = reranker.rerank("耳机", new ArrayList<>(List.of(a, b)), 5);

        assertThat(result).extracting(RetrievedChunk::getChunkId).containsExactly("a", "b");
        assertThat(result.get(0).getScore()).isEqualTo(0.50);
    }

    /**
     * 模型爱加 Markdown 代码块：取首个 '[' 到最后一个 ']' 之间照样能解析。
     */
    @Test
    void shouldParseFencedJson() {
        RetrievedChunk a = chunk("a", 0.50);
        RetrievedChunk b = chunk("b", 0.60);
        LlmReranker reranker = new LlmReranker(
                modelResponding("好的，以下是打分结果：\n```json\n[{\"i\":2,\"score\":0.9},{\"i\":1,\"score\":0.4}]\n```\n以上。"),
                properties);

        List<RetrievedChunk> result = reranker.rerank("耳机", new ArrayList<>(List.of(a, b)), 5);

        assertThat(result).extracting(RetrievedChunk::getChunkId).containsExactly("b", "a");
    }

    /**
     * 序号越界/重复/未提及的容错：越界与重复丢弃，未提及的记 0 分沉底。
     */
    @Test
    void shouldTolerateOutOfRangeDuplicateAndMissingIndices() {
        RetrievedChunk a = chunk("a", 0.50);
        RetrievedChunk b = chunk("b", 0.60);
        RetrievedChunk c = chunk("c", 0.70);
        // i=99 越界丢弃；i=1 重复取第一条；i=2 没提 → 0 分
        LlmReranker reranker = new LlmReranker(
                modelResponding("[{\"i\":99,\"score\":1.0},{\"i\":3,\"score\":0.6},{\"i\":1,\"score\":0.8},{\"i\":1,\"score\":0.2}]"),
                properties);

        List<RetrievedChunk> result = reranker.rerank("耳机", new ArrayList<>(List.of(a, b, c)), 5);

        assertThat(result).extracting(RetrievedChunk::getChunkId).containsExactly("a", "c", "b");
        assertThat(result.get(2).getScore()).isEqualTo(0.0);
    }

    /**
     * 分数裁剪到 0~1：越界分数不能进置信度计算。
     */
    @Test
    void shouldClampScoresIntoUnitRange() {
        RetrievedChunk a = chunk("片段A", 0.50);
        LlmReranker reranker = new LlmReranker(
                modelResponding("[{\"i\":1,\"score\":1.7}]"), properties);

        List<RetrievedChunk> result = reranker.rerank("耳机", new ArrayList<>(List.of(a)), 5);

        assertThat(result.get(0).getScore()).isEqualTo(1.0);
    }

    /**
     * 候选池封顶：超出 max-candidates 的候选不进提示词、也不出现在返回里。
     */
    @Test
    void shouldCapCandidatesSentToModel() {
        properties.getRerank().setMaxCandidates(3);
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(chunk("候选" + i, 0.9 - i * 0.1));
        }
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                capturedPrompt.set(request.messages().get(0).toString());
                return ChatResponse.builder().aiMessage(
                        AiMessage.from("[{\"i\":1,\"score\":0.9},{\"i\":2,\"score\":0.8},{\"i\":3,\"score\":0.7}]")).build();
            }
        };

        List<RetrievedChunk> result = new LlmReranker(capturing, properties).rerank("耳机", chunks, 5);

        assertThat(capturedPrompt.get()).contains("候选1").doesNotContain("候选4");
        assertThat(result).hasSize(3);
        assertThat(result).extracting(RetrievedChunk::getChunkId)
                .doesNotContain("候选4", "候选5");
    }

    /**
     * 返回数量受 topN 约束。
     */
    @Test
    void shouldTruncateToTopN() {
        RetrievedChunk a = chunk("片段A", 0.50);
        RetrievedChunk b = chunk("片段B", 0.60);
        RetrievedChunk c = chunk("片段C", 0.70);
        LlmReranker reranker = new LlmReranker(
                modelResponding("[{\"i\":1,\"score\":0.9},{\"i\":2,\"score\":0.8},{\"i\":3,\"score\":0.7}]"),
                properties);

        List<RetrievedChunk> result = reranker.rerank("耳机", new ArrayList<>(List.of(a, b, c)), 2);

        assertThat(result).hasSize(2);
    }

    /**
     * 空候选直接返回空，且不发起模型调用（省一次注定无意义的调用）。
     */
    @Test
    void shouldSkipModelCallForEmptyCandidates() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ChatModel counting = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                called.set(true);
                return ChatResponse.builder().aiMessage(AiMessage.from("[]")).build();
            }
        };

        List<RetrievedChunk> result = new LlmReranker(counting, properties).rerank("耳机", List.of(), 5);

        assertThat(result).isEmpty();
        assertThat(called.get()).as("空候选不该发起模型调用").isFalse();
    }

    /**
     * 提示词带专属标记且包含标题与截断后的正文：假端点与排查都靠它识别。
     */
    @Test
    void promptShouldCarryMarkerTitleAndTruncatedText() {
        properties.getRerank().setMaxChunkChars(10);
        RetrievedChunk a = chunk("七天无理由退换货规则", 0.5);
        a.setText("0123456789这段超出的部分会被截掉");
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                capturedPrompt.set(request.messages().get(0).toString());
                return ChatResponse.builder().aiMessage(AiMessage.from("[{\"i\":1,\"score\":0.9}]")).build();
            }
        };

        new LlmReranker(capturing, properties).rerank("退货", new ArrayList<>(List.of(a)), 5);

        assertThat(capturedPrompt.get())
                .contains(LlmReranker.PROMPT_MARKER)
                .contains("七天无理由退换货规则")
                .contains("0123456789")
                .doesNotContain("这段超出");
    }

    private static RetrievedChunk chunk(String chunkId, double score) {
        return RetrievedChunk.builder()
                .chunkId(chunkId).docId(1L).chunkNo(1)
                .docTitle("标题-" + chunkId).text(chunkId + " 的正文内容")
                .score(score).build();
    }

    private static ChatModel modelResponding(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }
}
