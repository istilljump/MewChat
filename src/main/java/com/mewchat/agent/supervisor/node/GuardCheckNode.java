package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.service.GuardrailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link ChatState#GUARD_CHECK} 节点：内容安全护栏。
 *
 * <p>在流程最前端拦住违规输入（违规交易、违禁品、代开发票这类试探）。
 * 位置是刻意的两处权衡：
 * <ul>
 *     <li>放在<b>意图识别之前</b>：违规内容不再送给大模型去理解。
 *         省一次调用只是附带好处，主要是不给模型"顺着违规话题往下答"的机会 ——
 *         护栏的价值在于内容根本没到模型手里</li>
 *     <li>放在<b>上下文加载之后</b>：违规消息此时已经落库，
 *         留下审计痕迹。若放在最前面，就查不到"谁在什么时候问了什么"</li>
 * </ul>
 *
 * <p><b>不修改置信度</b>：拒答是一个策略判定，不是对回答质量的评估。
 * 硬把它记成"高置信度"或"低置信度"都会让统计口径失真，
 * 因此这里只记录命中的词（进日志）与处理方，不碰 confidence。
 *
 * @author MewChat
 */
@Component
public class GuardCheckNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(GuardCheckNode.class);

    private final GuardrailService guardrailService;

    public GuardCheckNode(GuardrailService guardrailService) {
        this.guardrailService = guardrailService;
    }

    @Override
    public ChatState state() {
        return ChatState.GUARD_CHECK;
    }

    @Override
    public ChatState execute(ChatContext context) {
        Optional<String> hit = guardrailService.match(context.getUserMessage());
        if (hit.isEmpty()) {
            return ChatState.RESUME_CHECK;
        }

        // 命中的词只进服务端日志：不告诉用户"因为哪个词被拦"，否则等于把规则边界交出去
        log.warn("输入命中安全护栏：session={} 命中词={}", context.getSessionId(), hit.get());
        context.setHandlerAgent(getClass().getSimpleName());
        return ChatState.REJECT;
    }
}
