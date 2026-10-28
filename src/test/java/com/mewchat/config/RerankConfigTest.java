package com.mewchat.config;

import com.mewchat.rag.rerank.HeuristicReranker;
import com.mewchat.rag.rerank.LlmReranker;
import com.mewchat.rag.rerank.Reranker;
import com.mewchat.rag.retrieval.ChunkTokenizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排器装配开关的测试。
 *
 * <p>开关语义本身很薄，但它连着两条容易静默出错的路：
 * 配置写了 {@code llm} 却装配成启发式（LLM 重排从未生效，无任何报错）、
 * 或写错枚举值让上下文起不来。这里把"配置值 → 实现类"的映射钉死。
 *
 * @author MewChat
 */
class RerankConfigTest {

    private final RerankConfig config = new RerankConfig();

    private static final ChatModel ANY_MODEL = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new IllegalStateException("测试中不应发起模型调用");
        }
    };

    /**
     * 默认（heuristic）装配规则式重排器。
     */
    @Test
    void heuristicModeShouldWireHeuristicReranker() {
        RagProperties properties = new RagProperties();

        Reranker reranker = config.reranker(properties, new ChunkTokenizer(), ANY_MODEL);

        assertThat(reranker).isInstanceOf(HeuristicReranker.class);
    }

    /**
     * llm 模式装配 LLM 重排器。
     */
    @Test
    void llmModeShouldWireLlmReranker() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setMode(RagProperties.Rerank.Mode.LLM);

        Reranker reranker = config.reranker(properties, new ChunkTokenizer(), ANY_MODEL);

        assertThat(reranker).isInstanceOf(LlmReranker.class);
    }
}
