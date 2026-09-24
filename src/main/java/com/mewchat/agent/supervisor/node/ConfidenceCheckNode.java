package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * {@link ChatState#CONFIDENCE_CHECK} 节点：三档闸值决定本轮怎么收场。
 *
 * <p>这是"让 Agent 知道自己不知道"的关键一步，判断顺序固定：
 * <ol>
 *     <li><b>存在明确的信息缺口</b>（追问提示非空）→ 转追问。
 *         追问是<b>可靠</b>的处置方式，所以优先于一切置信度判断 ——
 *         缺订单号的时候，哪怕模型信心再足也问不出答案</li>
 *     <li><b>工具已返回确定性数据</b>（{@code success=true}）→ 直接生成回答。
 *         工具查到就是查到，其可信度不取决于意图打分；若被意图分拖到不达标线以下，
 *         就会出现"订单已查到却告诉用户处理不了"（见 {@link #execute} 中的说明）</li>
 *     <li><b>置信度达标</b>（≥ 达标线）→ 直接生成回答</li>
 *     <li><b>落在中档</b>（兜底线 ~ 达标线）→ 放宽条件补一次检索，回头再判一次</li>
 *     <li><b>其余</b>（低于兜底线，或补检索后仍不达标）→ 兜底：转人工 + 建工单</li>
 * </ol>
 *
 * <p>有效置信度取意图置信度与回答置信度的较小值（见
 * {@link ChatContext#effectiveConfidence()}）：连用户问什么都没识别准的时候，
 * 哪怕检索得分再高也不该当成本轮可信。
 *
 * <p><b>中档为什么值得"再试一次"而不是直接放弃</b>：中档的含义是"像是有依据、
 * 但不够扎实"，最常见的成因是首次召回的池子偏小、把本该作为依据的片段排在了门外。
 * 放宽召回范围重来一次，有机会把依据补足；补不上再降级也不迟。
 *
 * @author MewChat
 */
@Component
public class ConfidenceCheckNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceCheckNode.class);

    private final AgentProperties agentProperties;

    public ConfidenceCheckNode(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
        validateThresholds(agentProperties);
    }

    @Override
    public ChatState state() {
        return ChatState.CONFIDENCE_CHECK;
    }

    @Override
    public ChatState execute(ChatContext context) {
        if (StringUtils.hasText(context.getClarificationHint())) {
            log.debug("置信度校验：存在信息缺口，转追问");
            return ChatState.CLARIFY;
        }

        // 工具已经查到确定性数据时直接回复，不再看置信度闸值。
        //
        // 工具与检索的"可信"含义不同：检索分是相似度（0.6 分只说明"有点像"），
        // 而工具是精确查询 —— 查到就是查到，它的可信度与意图识别打的分无关。
        // 而有效置信度取 min(意图分, 回答分)，于是"意图分 0.60 + 工具查到"会算出 0.60、
        // 低于达标线 0.70 被判为不可信：订单数据就在上下文里却答复"处理不了、已转人工"，
        // 同一个问题还会进低置信度池、凭空多出一张工单。
        // 这与 RESUME_CHECK 里"明明查到了订单却走兜底"是同一类错误，处理方式也一致。
        //
        // 只认 success：查不到（notFound）与缺参数（needMoreInfo）的 success 都是 false，
        // 两者已被上面的 clarificationHint 分支接走，不会走到这里。
        if (context.getToolResult() != null && context.getToolResult().isSuccess()) {
            log.debug("工具已返回确定性数据，直接回复：session={} tool={} 意图分={}",
                    context.getSessionId(), context.getToolResult().getToolName(),
                    context.getIntentConfidence());
            return ChatState.REPLY;
        }

        BigDecimal confidence = context.effectiveConfidence();
        BigDecimal high = agentProperties.getHighConfidenceThreshold();
        BigDecimal low = agentProperties.getLowConfidenceThreshold();

        if (confidence.compareTo(high) >= 0) {
            log.debug("置信度达标：confidence={} high={}", confidence, high);
            return ChatState.REPLY;
        }

        if (shouldRetrieveAgain(context, low, high)) {
            // 置位后检索节点会放大召回范围；本标记同时保证只补检索一次
            context.setRetrievalRetry(true);
            log.info("置信度落在中档，补一次检索：session={} confidence={} 区间=[{}, {}) intent={}",
                    context.getSessionId(), confidence, low, high, context.getIntent());
            return ChatState.RAG_RETRIEVE;
        }

        log.info("置信度不足，转兜底：session={} confidence={} low={} intent={} 已补检索={}",
                context.getSessionId(), confidence, low, context.getIntent(), context.isRetrievalRetry());
        return ChatState.FALLBACK;
    }

    /**
     * 判断是否值得再做一次补充检索。
     *
     * <p>四个条件缺一不可，每个都对应一种"补了也没用"的情形：
     * <ol>
     *     <li>{@code 尚未补检索过} —— 只许补一次。反复放宽标准直到满意，
     *         等于把置信度变成"总能达标"的摆设</li>
     *     <li>{@code 有效置信度不低于兜底线} —— 连兜底线都没到，说明根本没有像样的依据，
     *         再放宽也只是捞上来一堆不相关内容</li>
     *     <li>{@code 回答置信度本身落在中档} —— <b>这一条最关键</b>：
     *         补检索只能改善检索分数，改善不了意图置信度。
     *         若限制来自"没识别准用户想问什么"（回答分很高但有效分被意图压低），
     *         补检索纯属浪费一次查询与一次重排，直接降级更诚实</li>
     *     <li>{@code 已经有召回片段} —— 检索一次都没召回到东西时，分数本就是 0，
     *         谈不上"补充"</li>
     * </ol>
     *
     * @param context 对话上下文
     * @param low     兜底线
     * @param high    达标线
     * @return true 表示应回到检索状态再试一次
     */
    private boolean shouldRetrieveAgain(ChatContext context, BigDecimal low, BigDecimal high) {
        if (context.isRetrievalRetry()) {
            return false;
        }
        BigDecimal answerScore = context.getAnswerConfidence();
        if (answerScore == null) {
            return false;
        }
        if (answerScore.compareTo(low) < 0 || answerScore.compareTo(high) >= 0) {
            return false;
        }
        return !CollectionUtils.isEmpty(context.getRetrievedChunks());
    }

    /**
     * 校验闸值配置。
     *
     * <p>放在构造阶段：达标线不高于兜底线时，"中档区间"为空或为负，
     * 三档逻辑会退化成两档甚至出现反直觉行为（例如分数越高反而越容易兜底）。
     * 这类配置错误必须在启动时炸掉，而不是靠某次线上对话去发现。
     *
     * @param properties 编排配置
     * @throws IllegalStateException 配置不合法时抛出
     */
    private static void validateThresholds(AgentProperties properties) {
        BigDecimal high = properties.getHighConfidenceThreshold();
        BigDecimal low = properties.getLowConfidenceThreshold();
        if (high == null || low == null) {
            throw new IllegalStateException("置信度闸值未配置：mewchat.agent.high/low-confidence-threshold");
        }
        if (high.compareTo(low) <= 0) {
            throw new IllegalStateException("置信度闸值配置有误：达标线 " + high
                    + " 必须大于兜底线 " + low + "，否则中档区间无意义");
        }
    }
}
