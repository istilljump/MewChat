package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.supervisor.IntentRecognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatState#INTENT_RECOGNIZE} 节点：意图识别 + 查询改写 + 参数抽取。
 *
 * <p>这三件事由同一次大模型调用完成，而不是拆成三次：
 * 它们都依赖"理解用户这句话"这同一个上下文，分开调用既要重复传历史、
 * 又可能得到互相矛盾的结论。
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

    public IntentRecognizeNode(IntentRecognizer intentRecognizer) {
        this.intentRecognizer = intentRecognizer;
    }

    @Override
    public ChatState state() {
        return ChatState.INTENT_RECOGNIZE;
    }

    @Override
    public ChatState execute(ChatContext context) {
        // 用消解后的文本：把"那这个能退吗"补全成完整问句后，意图判断才准
        IntentRecognizer.IntentResult result = intentRecognizer.recognize(
                context.effectiveQuery(), context.getHistory());

        context.setIntent(result.intent());
        context.setIntentConfidence(result.confidence());
        context.setRewrittenQuery(result.rewrittenQuery());
        context.setParams(result.params());

        log.debug("意图识别完成：session={} intent={} confidence={}",
                context.getSessionId(), result.intent(), result.confidence());
        return ChatState.ROUTE;
    }
}
