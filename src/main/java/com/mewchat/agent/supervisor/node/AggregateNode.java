package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.SubIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@link ChatState#AGGREGATE} 节点：多意图轮的聚合与受控环枢纽。
 *
 * <p>它做三件事：
 * <ol>
 *     <li><b>收割刚完成的段</b> —— 把上一节点写入的 {@code replyText} 与终态
 *     收进聚合列表并清空，否则下一段的生成节点会把它覆盖掉；</li>
 *     <li><b>决定环是否继续</b> —— 队列里还有子意图就弹出下一条写入上下文、
 *     返回 ROUTE（这是状态机里 REPLY ─▶ AGGREGATE ─▶ ROUTE 受控环的回边，
     * 圈数被意图识别阶段的 {@code multi-intent-max-sub-intents} 与全局
 *     {@code max-steps} 双重夹住）；</li>
 *     <li><b>拼接收尾</b> —— 队列空了就把各段按序拼成最终回复、按最差段
 *     确定本轮终态，返回 END。</li>
 * </ol>
 *
 * <p><b>终态取"最差段"</b>：FALLBACK &gt; CLARIFY &gt; REPLY。
 * 任何一段兜了底，本轮就按兜底收尾 —— 调度器据此建工单、把问题投进
 * 低置信度问题池（那一段确实没答上来，飞轮不该漏掉它）；
 * 而已答出来的段落仍然完整出现在回复里，用户不需要为"另一部分没答上"
 * 重新问一遍已得到答案的部分。
 *
 * <p><b>置信度取各段最小值</b>：聚合回复里只要有一段不可靠，
 * 整条回复对外的置信度就该反映这一点，而不是被答得最好的那段抬高。
 *
 * @author MewChat
 */
@Component
public class AggregateNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(AggregateNode.class);

    /** 各段之间的分隔：空行，让多段在 Markdown 渲染下形成自然分段 */
    private static final String SEGMENT_SEPARATOR = "\n\n";

    @Override
    public ChatState state() {
        return ChatState.AGGREGATE;
    }

    @Override
    public ChatState execute(ChatContext context) {
        // ① 收割刚完成的段。replyText 为空的段不收（不应发生，防御一次）
        if (StringUtils.hasText(context.getReplyText())) {
            context.getReplySegments().add(context.getReplyText());
            context.getSegmentStates().add(context.getFinalState());
            context.getSegmentIntentConfidences().add(context.getIntentConfidence());
            context.getSegmentAnswerConfidences().add(context.getAnswerConfidence());
            log.debug("聚合第 {} 段：state={} 长度={}",
                    context.getReplySegments().size(), context.getFinalState(),
                    context.getReplyText().length());
            context.setReplyText(null);
        }

        // ② 环继续：还有子意图就切换上下文到下一条，回到 ROUTE
        if (context.hasQueuedSubIntents()) {
            SubIntent next = context.getPendingSubIntents().poll();
            applyTo(context, next);
            log.debug("多意图轮继续：剩余={} 下一条={}",
                    context.getPendingSubIntents().size(), next.intent());
            return ChatState.ROUTE;
        }

        // ③ 拼接收尾
        List<String> segments = context.getReplySegments();
        if (segments.isEmpty()) {
            // 防御：没有任何段产出（理论上到不了）。按内部异常兜底处理，
            // 不让用户收到空回复
            log.error("多意图聚合时没有任何段产出，按兜底处理：session={}", context.getSessionId());
            context.setReplyText("抱歉，处理您的请求时出现了异常。建议稍后重试，或转接人工客服为您处理。");
            context.setFinalState(ChatState.FALLBACK);
            context.setHandoffRequired(true);
            return ChatState.END;
        }

        context.setReplyText(String.join(SEGMENT_SEPARATOR, segments));
        context.setFinalState(worstOf(context.getSegmentStates()));
        context.setIntentConfidence(minOf(context.getSegmentIntentConfidences()));
        context.setAnswerConfidence(minOf(context.getSegmentAnswerConfidences()));
        context.setHandoffRequired(context.isHandoffRequired()
                || context.getSegmentStates().contains(ChatState.FALLBACK));

        log.info("多意图聚合完成：session={} 段数={} 终态={}",
                context.getSessionId(), segments.size(), context.getFinalState());
        return ChatState.END;
    }

    /**
     * 把下一条子意图写入上下文，并重置"段级"工作字段。
     *
     * <p>必须重置的：工具结果、追问提示、回答置信度、补检索标记 ——
     * 它们都描述"当前这一段"，留着会让下一段继承上一段的失败/缺口
     * （例如上一段工具查不到、下一段是知识问答，却带着旧的工具追问提示走错分支）。
     * 刻意<b>不重置</b>的：{@code retrievedChunks}（引用来源是整轮的，聚合回复
     * 要把两条子问题各自的出处都带上）。
     *
     * @param context    对话上下文
     * @param subIntent 下一条子意图
     */
    private void applyTo(ChatContext context, SubIntent subIntent) {
        context.setIntent(subIntent.intent());
        context.setIntentConfidence(subIntent.confidence());
        context.setRewrittenQuery(StringUtils.hasText(subIntent.rewrittenQuery())
                ? subIntent.rewrittenQuery() : context.getUserMessage());
        context.setParams(subIntent.params() == null
                ? new java.util.HashMap<>() : new java.util.HashMap<>(subIntent.params()));

        context.setToolResult(null);
        context.setClarificationHint(null);
        context.setAnswerConfidence(null);
        context.setRetrievalRetry(false);
    }

    /**
     * 取最差的段终态：FALLBACK &gt; CLARIFY &gt; REPLY。
     *
     * @param states 各段终态
     * @return 最差终态
     */
    private ChatState worstOf(List<ChatState> states) {
        if (states.contains(ChatState.FALLBACK)) {
            return ChatState.FALLBACK;
        }
        if (states.contains(ChatState.CLARIFY)) {
            return ChatState.CLARIFY;
        }
        return ChatState.REPLY;
    }

    /**
     * 取列表中的最小值（null 不参与比较）。
     *
     * @param values 置信度列表
     * @return 最小值；列表为空或全 null 时返回 0
     */
    private BigDecimal minOf(List<BigDecimal> values) {
        BigDecimal min = null;
        for (BigDecimal value : values) {
            if (value != null && (min == null || value.compareTo(min) < 0)) {
                min = value;
            }
        }
        return min == null ? BigDecimal.ZERO : min;
    }
}
