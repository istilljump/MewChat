package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatState#ROUTE} 节点：按意图路由到处理节点。
 *
 * <p>路由规则不写在这里，而是由 {@link IntentType#getRouteTarget()} 声明 ——
 * 本节点只做一次查表。新增意图时只改枚举，路由逻辑不动，
 * 也就不会出现"加了意图却忘了加分支"这种问题。
 *
 * <p>唯一需要额外处理的是"识别不出意图"：除了返回追问状态，
 * 还要给出追问话术，否则追问节点无话可问。
 *
 * @author MewChat
 */
@Component
public class RouteNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(RouteNode.class);

    /** 意图无法识别时的追问话术，覆盖系统支持的主要能力，帮用户快速选一个 */
    private static final String UNKNOWN_INTENT_CLARIFY =
            "抱歉，我没太理解您的意思。您是想咨询退换货等平台规则，还是要查询订单、物流进度呢？"
                    + "可以直接告诉我订单号，我帮您查一下。";

    @Override
    public ChatState state() {
        return ChatState.ROUTE;
    }

    @Override
    public ChatState execute(ChatContext context) {
        IntentType intent = context.getIntent();
        ChatState target = intent.getRouteTarget();

        if (target == ChatState.CLARIFY && context.getClarificationHint() == null) {
            context.setClarificationHint(UNKNOWN_INTENT_CLARIFY);
        }

        log.debug("路由决策：session={} intent={} -> {}",
                context.getSessionId(), intent, target);
        return target;
    }
}
