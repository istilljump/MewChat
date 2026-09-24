package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link ChatState#CLARIFY} 节点：向用户追问，补齐缺失信息。
 *
 * <p>追问话术由上游节点写入 {@link ChatContext#getClarificationHint()}：
 * 工具节点知道缺的是订单号还是运单号，能问得更具体；
 * 这里只负责兜一个通用话术并结束本轮。
 *
 * <p><b>本节点不调用大模型</b>：追问内容是确定的（缺什么问什么），
 * 交给模型重写一遍只会引入不确定性、增加一次延迟和 token 消耗。
 *
 * @author MewChat
 */
@Component
public class ClarifyNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(ClarifyNode.class);

    /** 上游没有给出具体缺口时的通用追问 */
    private static final String DEFAULT_CLARIFY =
            "抱歉，我还需要一点信息才能帮您处理。麻烦您再补充一下具体情况，或者直接提供订单号。";

    @Override
    public ChatState state() {
        return ChatState.CLARIFY;
    }

    @Override
    public ChatState execute(ChatContext context) {
        String hint = context.getClarificationHint();
        context.setReplyText(StringUtils.hasText(hint) ? hint : DEFAULT_CLARIFY);

        log.debug("追问用户：session={} 缺口={}", context.getSessionId(), hint);
        return ChatState.END;
    }
}
