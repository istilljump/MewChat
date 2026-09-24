package com.mewchat.agent.specialist;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.config.RagProperties;
import com.mewchat.rag.retrieval.KnowledgeRetriever;
import com.mewchat.rag.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * RAG 专家：负责知识类意图的检索。
 *
 * <p>服务对象是"平台规则、政策说明"这类问题 —— 答案写在知识库里，
 * 靠检索找到依据再交给回复节点组织语言。
 *
 * <p><b>置信度直接取检索结果提供的值</b>，而不是自己拿召回最高分充当：
 * 检索置信度是"最高分 + 命中片段数量"综合出来的（见
 * {@code rag.retrieval.RetrievalConfidenceCalculator}），
 * 算法属于检索质量的一部分，应该和检索实现一起演进，
 * 编排层只负责比较它与阈值、决定是否兜底。
 *
 * <p><b>为什么用 {@link ObjectProvider} 软依赖</b>：检索实现由 rag 模块提供，
 * 软依赖让"rag 模块被裁剪/未装配"时应用仍能启动，本节点按"无召回"处理、
 * 置信度置 0，流程自然走到兜底 —— 这恰好也是"知识库为空"时应有的行为。
 *
 * @author MewChat
 */
@Component
public class RagSpecialist implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(RagSpecialist.class);

    private final ObjectProvider<KnowledgeRetriever> retrieverProvider;

    private final RagProperties ragProperties;

    public RagSpecialist(ObjectProvider<KnowledgeRetriever> retrieverProvider,
                         RagProperties ragProperties) {
        this.retrieverProvider = retrieverProvider;
        this.ragProperties = ragProperties;
    }

    @Override
    public ChatState state() {
        return ChatState.RAG_RETRIEVE;
    }

    @Override
    public ChatState execute(ChatContext context) {
        context.setHandlerAgent(getClass().getSimpleName());

        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            log.warn("知识检索服务不可用，本轮按无召回处理：session={}", context.getSessionId());
            context.setAnswerConfidence(BigDecimal.ZERO);
            return ChatState.CONFIDENCE_CHECK;
        }

        RetrievalResult result;
        try {
            result = retriever.retrieve(context.effectiveQuery(), currentTopK(context));
        } catch (Exception e) {
            // 检索失败同样降级为"无召回"，而不是让整轮对话崩掉
            log.error("知识检索失败：session={}", context.getSessionId(), e);
            context.setErrorMessage("知识检索失败：" + e.getMessage());
            context.setAnswerConfidence(BigDecimal.ZERO);
            return ChatState.CONFIDENCE_CHECK;
        }

        if (result == null || result.isEmpty()) {
            context.setAnswerConfidence(BigDecimal.ZERO);
            log.debug("知识检索无召回：session={} query={}",
                    context.getSessionId(), context.effectiveQuery());
            return ChatState.CONFIDENCE_CHECK;
        }

        context.setRetrievedChunks(result.getChunks());
        context.setAnswerConfidence(result.getConfidence());
        log.debug("知识检索完成：session={} 召回 {} 条 置信度={} 补检索={}",
                context.getSessionId(), result.getChunks().size(), result.getConfidence(),
                context.isRetrievalRetry());
        return ChatState.CONFIDENCE_CHECK;
    }

    /**
     * 本轮检索应使用的片段条数。
     *
     * <p>补检索时放大召回范围（{@code topK × retry-top-k-multiplier}）：
     * 置信度落在中档最常见的原因，就是首次召回的池子偏小、
     * 把本该作为依据的片段排在了门外。放大一次有机会把依据补足。
     *
     * <p>判断依据取自上下文里显式的补检索标记，而不是去数状态轨迹里出现过几次检索 ——
     * 拿轨迹当控制条件，会让"这是第几次检索"这种关键逻辑藏在历史记录里，
     * 改动流程时极容易被忽略。
     *
     * @param context 对话上下文
     * @return 片段条数
     */
    private int currentTopK(ChatContext context) {
        int topK = ragProperties.getTopK();
        if (!context.isRetrievalRetry()) {
            return topK;
        }
        int widened = topK * Math.max(1, ragProperties.getRetryTopKMultiplier());
        log.debug("补充检索：放大召回范围 {} -> {}", topK, widened);
        return widened;
    }
}
