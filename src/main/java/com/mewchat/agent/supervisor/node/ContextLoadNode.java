package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.memory.ChatMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatState#CONTEXT_LOAD} 节点：加载会话上下文。
 *
 * <p>做四件事，顺序不能乱：
 * <ol>
 *     <li>确保会话记录存在（首次对话时创建）</li>
 *     <li>加载短时记忆（最近若干轮）与长时记忆（摘要）</li>
 *     <li>做指代消解 —— 必须在加载历史之后，否则没有上下文可消解</li>
 *     <li>保存本轮用户消息 —— 必须在加载历史<b>之后</b>，
 *         否则本轮输入会被当成历史、在提示词里重复出现一次</li>
 * </ol>
 *
 * <p>本节点是流程中唯一写入用户消息的地方，把它放在这里而不是等流程结束统一落库，
 * 是为了让用户输入尽早持久化：即使后续节点抛异常，对话记录里也留有用户的提问。
 *
 * @author MewChat
 */
@Component
public class ContextLoadNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(ContextLoadNode.class);

    private final ChatMemoryService chatMemoryService;

    public ContextLoadNode(ChatMemoryService chatMemoryService) {
        this.chatMemoryService = chatMemoryService;
    }

    @Override
    public ChatState state() {
        return ChatState.CONTEXT_LOAD;
    }

    @Override
    public ChatState execute(ChatContext context) {
        String sessionId = context.getSessionId();

        chatMemoryService.ensureConversation(sessionId, context.getUserId());

        context.setHistory(chatMemoryService.loadRecentHistory(sessionId));
        context.setSummary(chatMemoryService.loadSummary(sessionId));

        context.setResolvedMessage(
                chatMemoryService.resolveReferences(sessionId, context.getUserMessage()));

        chatMemoryService.saveUserMessage(sessionId, context.getUserMessage());

        log.debug("上下文加载完成：session={} 历史 {} 轮 摘要={}",
                sessionId, context.getHistory().size(), context.getSummary() != null);
        // 出口是护栏校验而不是意图识别：转移表里 CONTEXT_LOAD 只允许到 GUARD_CHECK，
        // 违规输入必须在进模型之前被拦下（见 ChatState 的流转图）
        return ChatState.GUARD_CHECK;
    }
}
