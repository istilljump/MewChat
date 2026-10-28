package com.mewchat.config;

import com.mewchat.rag.rerank.HeuristicReranker;
import com.mewchat.rag.rerank.LlmReranker;
import com.mewchat.rag.rerank.Reranker;
import com.mewchat.rag.retrieval.ChunkTokenizer;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重排器装配：按 {@code mewchat.rag.rerank.mode} 选择实现。
 *
 * <p>两个实现类都<b>不标 {@code @Component}</b>、由这里统一装配：
 * 模式开关只有一个判定点，不会出现"两个 Reranker Bean 让注入点歧义"的问题；
 * 同时这里也是打启动日志的地方 —— 阶段 23 的教训：功能开关必须打启动日志，
 * 否则第一轮端到端跑的是哪条路径完全无从判断。
 *
 * <p>LLM 模式下直接注入 {@link ChatModel}（与回复生成同一个 Bean）：
 * 重排调用与业务调用共享超时、重试与厂商配置，不为它单开一份模型客户端。
 *
 * @author MewChat
 */
@Configuration
public class RerankConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankConfig.class);

    /**
     * 按配置选择重排器。
     *
     * @param ragProperties RAG 配置（含 rerank.mode）
     * @param tokenizer     分词器（启发式实现需要）
     * @param chatModel     模型客户端（LLM 实现需要）
     * @return 选定的重排器
     */
    @Bean
    public Reranker reranker(RagProperties ragProperties,
                             ChunkTokenizer tokenizer,
                             ChatModel chatModel) {
        RagProperties.Rerank rerank = ragProperties.getRerank();
        Reranker chosen = rerank.getMode() == RagProperties.Rerank.Mode.LLM
                ? new LlmReranker(chatModel, ragProperties)
                : new HeuristicReranker(tokenizer);
        log.info("重排器：{}（mewchat.rag.rerank.mode={}）",
                chosen.getClass().getSimpleName(), rerank.getMode());
        return chosen;
    }
}
