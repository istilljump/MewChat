package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.service.GuardrailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatState#REJECT} 节点：拒答。
 *
 * <p>与 {@link FallbackNode} 的区别是这个节点的全部意义所在：
 * <ul>
 *     <li>兜底的含义是"我答不上来"，因此要转人工、要建工单、要把问题记进低置信度池
 *         （它是知识缺口，值得补知识）</li>
 *     <li>拒答的含义是"这类内容我不回答"，是<b>策略决定</b>，不是能力不足。
 *         因此不转人工、不建工单、也不进低置信度池 ——
 *         把违规提问当成"知识盲区"喂进补知识流程，只会污染那套数据飞轮</li>
 * </ul>
 *
 * <p><b>不调用大模型</b>：拒答话术必须是稳定、可控、无歧义的。
 * 让模型去"委婉地拒绝"，就有概率被话术绕过，或者把违规内容复述一遍。
 *
 * @author MewChat
 */
@Component
public class RejectNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(RejectNode.class);

    private final GuardrailService guardrailService;

    public RejectNode(GuardrailService guardrailService) {
        this.guardrailService = guardrailService;
    }

    @Override
    public ChatState state() {
        return ChatState.REJECT;
    }

    @Override
    public ChatState execute(ChatContext context) {
        context.setReplyText(guardrailService.rejectReply());
        // 显式置 false：兜底分支会把它置 true，两条分支的差别必须由代码表达清楚，
        // 而不是依赖"默认值恰好是 false"
        context.setHandoffRequired(false);

        log.info("拒答：session={} 命中护栏，不转人工、不建工单", context.getSessionId());
        return ChatState.END;
    }
}
