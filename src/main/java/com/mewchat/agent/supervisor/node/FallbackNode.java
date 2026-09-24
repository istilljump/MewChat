package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatState#FALLBACK} 节点：兜底处理。
 *
 * <p>走到这里的含义是"本轮没能给出可靠答案"。兜底不等于说一句"我不知道"就完事，
 * 它要把一次失败变成三件有价值的流转：
 * <ul>
 *     <li>坦诚告知用户没答上，并给出转人工这条明确的下一步</li>
 *     <li>把 {@code handoffRequired} 置为 true，前端据此展示"转人工"按钮</li>
 *     <li>调度器在流程收尾时会做两件事：把问题写入低置信度问题池（补知识的依据）、
 *         创建人工工单（保证有人接手）。<b>工单不是本节点建的</b> ——
 *         按节点约定，节点只做决策与计算，持久化统一由调度器负责</li>
 * </ul>
 *
 * <p><b>本节点不调用大模型</b>：兜底话术必须稳定可控。而且此时模型本身往往就不可靠
 * （超时、限流正是走到这里的常见原因），再去调它一次多半还是失败。
 *
 * <p><b>话术区分两种成因，虽然需求只给了一句</b>：
 * 没找到依据时说"知识库中没有找到可靠答案"是准确的；
 * 但模型超时、工具报错时说同一句话就是<b>误导</b> ——
 * 问题不在知识库，而在系统。用户按"知识库没有"去理解，会反复重问同一个问题。
 * 因此故障场景用另一句，两者都不带任何内部细节。
 *
 * <p>与 {@link RejectNode} 的区别见后者注释：兜底是"答不上来"，拒答是"不回答这类内容"。
 *
 * @author MewChat
 */
@Component
public class FallbackNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(FallbackNode.class);

    /** 没有可靠依据时的兜底话术（对客固定话术，不随轮次变化） */
    private static final String NO_RELIABLE_ANSWER_REPLY =
            "抱歉，知识库中没有找到可靠答案，已为您转接人工客服";

    /** 系统故障（模型/工具不可用）时的兜底话术，见类注释的说明 */
    private static final String SYSTEM_ERROR_REPLY =
            "抱歉，系统暂时有点忙，我没能完成这次处理。"
                    + "已为您转接人工客服，客服会尽快为您处理。";

    @Override
    public ChatState state() {
        return ChatState.FALLBACK;
    }

    @Override
    public ChatState execute(ChatContext context) {
        boolean systemFailure = context.getErrorMessage() != null;
        context.setReplyText(systemFailure ? SYSTEM_ERROR_REPLY : NO_RELIABLE_ANSWER_REPLY);
        context.setHandoffRequired(true);

        log.info("兜底处理：session={} 成因={} intent={} confidence={}",
                context.getSessionId(),
                systemFailure ? "系统故障" : "无可靠依据",
                context.getIntent(),
                context.effectiveConfidence());
        return ChatState.END;
    }
}
