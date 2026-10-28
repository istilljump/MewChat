package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.SubIntent;
import com.mewchat.agent.supervisor.IntentRecognizer;
import com.mewchat.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ChatState#INTENT_RECOGNIZE} 节点：意图识别 + 查询改写 + 参数抽取。
 *
 * <p>这三件事由同一次大模型调用完成，而不是拆成三次：
 * 它们都依赖"理解用户这句话"这同一个上下文，分开调用既要重复传历史、
 * 又可能得到互相矛盾的结论。
 *
 * <p><b>多意图模式</b>（{@code mewchat.agent.multi-intent}，默认关闭）开启后，
 * 本节点改走 {@link IntentRecognizer#recognizeAll}：一句话拆出的多个子意图中，
 * 第一条写入上下文按正常流程走，余下的进入
 * {@link ChatContext#getPendingSubIntents()} 队列，由 {@code AGGREGATE} 节点
 * 的受控环逐条处理。关闭时行为与引入该特性之前完全一致。
 *
 * <p>识别结果写入上下文后，本节点<b>一律返回 ROUTE</b>。
 * 识别失败不作为分支处理：{@link IntentRecognizer} 已保证失败时返回 UNKNOWN，
 * 由路由节点统一把 UNKNOWN 映射到追问分支，这里不需要额外判断。
 *
 * @author MewChat
 */
@Component
public class IntentRecognizeNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizeNode.class);

    private final IntentRecognizer intentRecognizer;

    private final AgentProperties agentProperties;

    public IntentRecognizeNode(IntentRecognizer intentRecognizer, AgentProperties agentProperties) {
        this.intentRecognizer = intentRecognizer;
        this.agentProperties = agentProperties;
        // 功能开关必须打启动日志（阶段 23 的教训）：否则第一轮跑的是哪条路径无从判断
        log.info("意图识别模式：{}（multi-intent={}，上限 {} 条）",
                agentProperties.isMultiIntent() ? "多意图拆解" : "单意图",
                agentProperties.isMultiIntent(), agentProperties.getMultiIntentMaxSubIntents());
    }

    @Override
    public ChatState state() {
        return ChatState.INTENT_RECOGNIZE;
    }

    @Override
    public ChatState execute(ChatContext context) {
        // 用消解后的文本：把"那这个能退吗"补全成完整问句后，意图判断才准
        String query = context.effectiveQuery();

        if (!agentProperties.isMultiIntent()) {
            IntentRecognizer.IntentResult result = intentRecognizer.recognize(query, context.getHistory());
            applyResult(context, result);
            return ChatState.ROUTE;
        }

        List<IntentRecognizer.IntentResult> results = intentRecognizer.recognizeAll(
                query, context.getHistory(), agentProperties.getMultiIntentMaxSubIntents());
        applyResult(context, results.get(0));
        // 余下的进队列：第一条之外才叫"子意图"，单条结果队列为空、后续节点走原路
        for (int i = 1; i < results.size(); i++) {
            IntentRecognizer.IntentResult item = results.get(i);
            context.getPendingSubIntents().add(new SubIntent(
                    item.intent(), item.confidence(), item.rewrittenQuery(), item.params()));
        }
        if (context.hasQueuedSubIntents()) {
            // multiIntentTurn 标志驱动段节点走 AGGREGATE（含最后一段 ——
            // 它也要进聚合才能完成拼接），见 ChatContext 的字段注释
            context.setMultiIntentTurn(true);
            log.info("多意图拆解：session={} 共 {} 条子意图（余 {} 条排队）",
                    context.getSessionId(), results.size(), context.getPendingSubIntents().size());
        }
        return ChatState.ROUTE;
    }

    /**
     * 把识别结果写入上下文。
     *
     * @param context 对话上下文
     * @param result  识别结果
     */
    private void applyResult(ChatContext context, IntentRecognizer.IntentResult result) {
        context.setIntent(result.intent());
        context.setIntentConfidence(result.confidence());
        context.setRewrittenQuery(result.rewrittenQuery());
        context.setParams(result.params());

        log.debug("意图识别完成：session={} intent={} confidence={}",
                context.getSessionId(), result.intent(), result.confidence());
    }
}
