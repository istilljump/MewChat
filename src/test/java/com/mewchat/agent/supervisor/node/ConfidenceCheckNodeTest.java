package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.config.AgentProperties;
import com.mewchat.rag.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 置信度三档闸值的单元测试（纯单元测试，不依赖 Spring 与数据库）。
 *
 * <p>这是"不乱说"这条要求的落点。它的三条分支里，最容易写错的是<b>中档</b>：
 * 什么情况下值得再检索一次、什么情况下再检索也是白费。
 * 这些判断错了不会报错，只会表现为"有些问题莫名其妙转人工"，
 * 或者"系统反复检索、最后还是答不上来"。
 *
 * @author MewChat
 */
class ConfidenceCheckNodeTest {

    private final AgentProperties properties = new AgentProperties();

    private final ConfidenceCheckNode node = new ConfidenceCheckNode(properties);

    /* ==================== 三档 ==================== */

    /**
     * 达标：直接生成回答。
     */
    @Test
    void highConfidenceShouldReply() {
        ChatContext context = contextOf("0.90", "0.95");

        assertThat(node.execute(context)).isEqualTo(ChatState.REPLY);
        assertThat(context.isRetrievalRetry()).as("达标时不该触发补充检索").isFalse();
    }

    /**
     * 中档：回答分数落在 [兜底线, 达标线) 且已有召回时，补一次检索再判。
     */
    @Test
    void midAnswerScoreShouldRetrieveAgain() {
        ChatContext context = contextOf("0.90", "0.55");

        assertThat(node.execute(context)).isEqualTo(ChatState.RAG_RETRIEVE);
        assertThat(context.isRetrievalRetry())
                .as("必须置位补检索标记，否则会无限循环或检索不到放大后的范围")
                .isTrue();
    }

    /**
     * 不达标：低于兜底线时直接兜底，不做无意义的补检索。
     */
    @Test
    void belowLowThresholdShouldFallback() {
        ChatContext context = contextOf("0.90", "0.20");

        assertThat(node.execute(context)).isEqualTo(ChatState.FALLBACK);
        assertThat(context.isRetrievalRetry()).isFalse();
    }

    /**
     * 完全无依据（置信度 0）同样兜底。
     */
    @Test
    void zeroConfidenceShouldFallback() {
        assertThat(node.execute(contextOf("0.90", "0.00"))).isEqualTo(ChatState.FALLBACK);
    }

    /* ==================== 中档的边界与例外 ==================== */

    /**
     * 已经补过一次检索仍落在中档 → 兜底，<b>不再放宽标准</b>。
     */
    @Test
    void alreadyRetriedShouldFallback() {
        ChatContext context = contextOf("0.90", "0.55");
        context.setRetrievalRetry(true);

        assertThat(node.execute(context)).isEqualTo(ChatState.FALLBACK);
    }

    /**
     * 限制来自"意图没识别准"时不该补检索。
     *
     * <p>补检索只能改善检索分数，改善不了意图置信度：这里的回答分是 0.95（很扎实），
     * 但因为意图分只有 0.50，有效置信度被压到 0.50 落进中档。
     * 再检索一次，有效置信度依然是 min(0.50, ...) = 0.50 —— 白跑一趟。
     */
    @Test
    void midIntentWithStrongAnswerShouldNotRetrieveAgain() {
        ChatContext context = contextOf("0.50", "0.95");

        assertThat(node.execute(context)).isEqualTo(ChatState.FALLBACK);
        assertThat(context.isRetrievalRetry()).isFalse();
    }

    /**
     * 没有召回片段时无从"补充"，直接兜底。
     */
    @Test
    void midScoreWithoutChunksShouldFallback() {
        ChatContext context = ChatContext.builder()
                .sessionId("s")
                .userMessage("问题")
                .intent(IntentType.KNOWLEDGE_QA)
                .intentConfidence(new BigDecimal("0.90"))
                .answerConfidence(new BigDecimal("0.55"))
                .build();

        assertThat(node.execute(context)).isEqualTo(ChatState.FALLBACK);
    }

    /* ==================== 追问优先 ==================== */

    /**
     * 存在信息缺口时，追问优先于一切置信度判断。
     *
     * <p>缺订单号的时候，哪怕模型信心再足也问不出答案 ——
     * 这条优先级的顺序错了，用户就会收到一个基于空参数编出来的回答。
     */
    @Test
    void clarificationHintShouldTakePrecedenceOverHighConfidence() {
        ChatContext context = contextOf("0.99", "0.99");
        context.setClarificationHint("请提供订单号");

        assertThat(node.execute(context)).isEqualTo(ChatState.CLARIFY);
        assertThat(context.isRetrievalRetry()).isFalse();
    }

    /* ==================== 配置校验 ==================== */

    /**
     * 闸值写反必须在启动时报错。
     *
     * <p>达标线不高于兜底线时，"中档区间"为空或为负，三档逻辑会退化成
     * 反直觉的行为（分数越高越容易兜底）。这类配置错误必须炸在启动阶段。
     */
    @Test
    void invalidThresholdsShouldFailStartup() {
        AgentProperties broken = new AgentProperties();
        broken.setHighConfidenceThreshold(new BigDecimal("0.30"));
        broken.setLowConfidenceThreshold(new BigDecimal("0.60"));

        assertThatThrownBy(() -> new ConfidenceCheckNode(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("达标线");
    }

    /**
     * 闸值相等同样不合法（中档区间为空）。
     */
    @Test
    void equalThresholdsShouldFailStartup() {
        AgentProperties broken = new AgentProperties();
        broken.setHighConfidenceThreshold(new BigDecimal("0.50"));
        broken.setLowConfidenceThreshold(new BigDecimal("0.50"));

        assertThatThrownBy(() -> new ConfidenceCheckNode(broken))
                .isInstanceOf(IllegalStateException.class);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造带召回片段的对话上下文。
     *
     * @param intentScore 意图置信度
     * @param answerScore 回答置信度
     * @return 对话上下文
     */
    private ChatContext contextOf(String intentScore, String answerScore) {
        return ChatContext.builder()
                .sessionId("test-session")
                .userMessage("七天无理由退货怎么操作")
                .intent(IntentType.KNOWLEDGE_QA)
                .intentConfidence(new BigDecimal(intentScore))
                .answerConfidence(new BigDecimal(answerScore))
                .retrievedChunks(List.of(RetrievedChunk.builder()
                        .chunkId("1_1")
                        .docId(1L)
                        .docTitle("七天无理由退换货规则")
                        .chunkNo(1)
                        .score(0.55)
                        .text("签收之日起 7 天内可申请无理由退货。")
                        .build()))
                .build();
    }
}
