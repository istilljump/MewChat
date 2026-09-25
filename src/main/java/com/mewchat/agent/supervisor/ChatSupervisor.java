package com.mewchat.agent.supervisor;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.agent.memory.ChatMemoryService;
import com.mewchat.common.constant.ChatConstants;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.observability.ChatTurnTrace;
import com.mewchat.common.observability.LangfuseClient;
import com.mewchat.common.result.ResultCode;
import com.mewchat.config.AgentProperties;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 对话调度器 —— 整个 Agent 编排的核心。
 *
 * <p><b>职责边界</b>：本类只做三件事 —— 驱动状态机、校验转移合法性、在流程首尾做持久化。
 * 具体每一步怎么算，都在各自的 {@link ChatNode} 里；本类不含任何业务判断。
 *
 * <p><b>为什么流程不由大模型控制</b>：把"下一步做什么"交给模型意味着
 * 每次回答的路径都可能不同，同样的输入可能得到不同结果，出了问题也无从复现。
 * 这里反过来：模型只在意图识别、文本生成、记忆压缩三个明确的点上被调用，
 * 每次调用都有严格的输出校验与降级路径；
 * 而<b>流程走向完全由 {@link ChatState} 的转移表决定</b>。
 * 这样任何一轮对话的路径都能在 {@code visitedStates} 里被完整还原。
 *
 * <p><b>三道防线保证"永远能返回点什么"</b>：
 * <ol>
 *     <li>启动时：校验每个非终态都有且只有一个节点，缺了就起不来（问题暴露在开发期）</li>
 *     <li>运行时：每次转移都校验是否在转移表内，跳错状态立即抛异常</li>
 *     <li>兜底：流程中任何未预期异常都被收敛成一次兜底回复，
 *         用户绝不会收到空回复或 500</li>
 * </ol>
 *
 * <p><b>流式与同步只差一个回调</b>：{@link #processStream} 与 {@link #process}
 * 走的是<b>同一个状态机、同一批节点</b>，唯一区别是前者在上下文里挂了
 * {@link ChatStreamListener}。不另写一条流式链路是有意的 ——
 * 两条链路意味着两套分支逻辑，而它们迟早会因为只改了一边而行为不一致。
 *
 * @author MewChat
 */
@Service
public class ChatSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ChatSupervisor.class);

    /** 流程内部异常时的兜底话术 */
    private static final String INTERNAL_ERROR_REPLY =
            "抱歉，处理您的请求时出现了异常。建议稍后重试，或转接人工客服为您处理。";

    /** 状态 → 节点 的映射，启动时构建并校验后不可变 */
    private final Map<ChatState, ChatNode> nodeMap;

    private final ChatMemoryService chatMemoryService;

    private final LowConfidenceQuestionService lowConfidenceQuestionService;

    private final TicketService ticketService;

    private final LangfuseClient langfuseClient;

    private final AgentProperties agentProperties;

    public ChatSupervisor(List<ChatNode> nodes,
                          ChatMemoryService chatMemoryService,
                          LowConfidenceQuestionService lowConfidenceQuestionService,
                          TicketService ticketService,
                          LangfuseClient langfuseClient,
                          AgentProperties agentProperties) {
        this.nodeMap = buildNodeMap(nodes);
        this.chatMemoryService = chatMemoryService;
        this.lowConfidenceQuestionService = lowConfidenceQuestionService;
        this.ticketService = ticketService;
        this.langfuseClient = langfuseClient;
        this.agentProperties = agentProperties;
    }

    /**
     * 处理一轮对话（无用户身份）。
     *
     * <p>保留给定时任务、测试等没有登录用户的调用场景。
     *
     * @param sessionId   会话业务ID
     * @param userMessage 用户输入
     * @return 处理结果
     */
    public ChatReply process(String sessionId, String userMessage) {
        return process(sessionId, null, userMessage);
    }

    /**
     * 处理一轮对话，一次性返回完整结果。
     *
     * @param sessionId   会话业务ID
     * @param userId      当前登录用户ID，游客传 null。会写入会话记录，用于归属校验
     * @param userMessage 用户输入
     * @return 处理结果，包含回复文本与可观测信息
     * @throws BizException 入参不合法时抛出，由全局异常处理器转成统一响应
     */
    public ChatReply process(String sessionId, Long userId, String userMessage) {
        return doProcess(sessionId, userId, userMessage, null);
    }

    /**
     * 处理一轮对话，<b>边生成边通过回调推送</b>。
     *
     * <p>本方法仍然是同步的：回调全部结束、流程走到终态后才返回。
     * 它不负责把结果送到哪儿去 —— 那是 {@link ChatStreamListener} 实现方的事，
     * 编排层不认识 HTTP。
     *
     * @param sessionId   会话业务ID
     * @param userId      当前登录用户ID
     * @param userMessage 用户输入
     * @param listener    流式输出回调，<b>不可为 null</b>（不需要流式请调 {@link #process}）
     * @return 处理结果，与 {@link #process} 完全一致
     * @throws IllegalArgumentException listener 为 null 时抛出
     * @throws BizException             入参不合法时抛出
     */
    public ChatReply processStream(String sessionId, Long userId, String userMessage,
                                   ChatStreamListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("流式回调不能为空：不需要流式请调用 process(...)");
        }
        return doProcess(sessionId, userId, userMessage, listener);
    }

    /**
     * 两个对外入口的共同实现。
     *
     * @param sessionId   会话业务ID
     * @param userId      用户ID，可为 null
     * @param userMessage 用户输入
     * @param listener    流式回调，非流式时为 null
     * @return 处理结果
     */
    private ChatReply doProcess(String sessionId, Long userId, String userMessage,
                                ChatStreamListener listener) {
        validateInput(sessionId, userMessage);

        long startAt = System.currentTimeMillis();
        ChatContext context = ChatContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .userMessage(userMessage)
                .streamListener(listener)
                .build();

        runStateMachine(context);
        context.setCostMs(System.currentTimeMillis() - startAt);

        persist(context);
        reportTurn(context);

        log.info("对话处理完成：session={} intent={} 状态轨迹={} 置信度={} 耗时={}ms 流式={}",
                sessionId, context.getIntent(), context.getVisitedStates(),
                context.effectiveConfidence(), context.getCostMs(), listener != null);
        return buildReply(context);
    }

    /**
     * 上报本轮对话的可观测摘要。
     *
     * <p><b>为什么上报放在这里而不是接口层</b>：这轮对话最完整的画像在上下文里 ——
     * 走了哪条工具、检索命中几条、置信度多少、token 花了多少。
     * 换到接口层就得先把它们塞进 {@link ChatReply} 再搬一遍，
     * 而那些字段除了上报没人用。放在这里也顺带覆盖了所有入口
     * （将来若加一条同步接口，不必记得补埋点）。
     *
     * <p>与 {@link #persist} 并列而不是并入其中：落库是业务数据，上报是可观测数据，
     * 两者的失败后果完全不同 —— 上面那样分开写，读代码时一眼能看出
     * "上报坏了不影响对话"。
     *
     * @param context 对话上下文
     */
    private void reportTurn(ChatContext context) {
        try {
            langfuseClient.recordTurn(new ChatTurnTrace(
                    context.getSessionId(),
                    context.getUserId(),
                    context.getUserMessage(),
                    context.getReplyText(),
                    context.getIntent() == null ? null : context.getIntent().name(),
                    context.getFinalState() == null ? null : context.getFinalState().name(),
                    context.getHandlerAgent(),
                    context.getToolResult() == null ? null : context.getToolResult().getToolName(),
                    context.getToolResult() == null ? null : context.getToolResult().isSuccess(),
                    context.getRetrievedChunks().size(),
                    context.effectiveConfidence(),
                    context.getCostMs(),
                    context.getTotalTokens(),
                    context.getModelName()));
        } catch (Exception e) {
            // 兜底再包一层：客户端内部已经吞掉了网络异常，这里防的是构造摘要本身出错。
            // 可观测性在任何情况下都不该影响一次成功的对话
            log.warn("可观测上报失败（不影响本轮对话）：session={}", context.getSessionId(), e);
        }
    }

    /* ==================== 状态机驱动 ==================== */

    /**
     * 驱动状态机直至终态。
     *
     * <p>循环体只做"取节点 → 执行 → 校验转移"三件事。
     * 任何异常都在此收敛为一次兜底，保证调用方总能拿到可返回给用户的内容。
     *
     * @param context 对话上下文
     */
    private void runStateMachine(ChatContext context) {
        ChatState state = ChatState.CONTEXT_LOAD;
        enterState(context, state);
        int steps = 0;

        try {
            while (!state.isTerminal()) {
                if (++steps > agentProperties.getMaxSteps()) {
                    throw new IllegalStateException("状态机步数超过上限 " + agentProperties.getMaxSteps()
                            + "，当前轨迹：" + context.getVisitedStates());
                }

                ChatNode node = nodeMap.get(state);
                if (node == null) {
                    throw new IllegalStateException("状态 " + state + " 没有对应的处理节点");
                }

                ChatState next = node.execute(context);

                if (!state.canTransitionTo(next)) {
                    throw new IllegalStateException("非法的状态转移：" + state + " -> " + next
                            + "，该状态允许的后继为 " + state.nextStates());
                }

                // 节点返回终态，说明是"当前这个节点"产出了本轮答案。
                // 记录的是 state 而不是 next —— next 是 END，记录它没有意义
                if (next.isTerminal()) {
                    context.setFinalState(state);
                }

                state = next;
                enterState(context, state);
            }
        } catch (Exception e) {
            // 把未预期异常变成一次正常的兜底回复，而不是让用户面对 500。
            // 轨迹里补记 FALLBACK，这样从 visitedStates 就能看出这轮是异常降级的
            log.error("对话流程异常：session={} 轨迹={}", context.getSessionId(),
                    context.getVisitedStates(), e);
            context.setErrorMessage(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
            context.setReplyText(INTERNAL_ERROR_REPLY);
            context.setHandoffRequired(true);
            context.setFinalState(ChatState.FALLBACK);
            enterState(context, ChatState.FALLBACK);
        }
    }

    /**
     * 记录并广播"进入了某个状态"。
     *
     * <p>把两件事绑在一个方法里，是为了保证它们不会被漏掉其中一个：
     * 轨迹是排查问题的依据，广播是前端进度提示的依据，
     * 少记一次轨迹会让日志看起来"流程跳了一步"，少广播一次会让前端进度卡住。
     *
     * @param context 对话上下文
     * @param state   进入的状态
     */
    private void enterState(ChatContext context, ChatState state) {
        context.markVisited(state);
        ChatStreamListener listener = context.getStreamListener();
        if (listener != null) {
            listener.onState(state);
        }
    }

    /* ==================== 持久化 ==================== */

    /**
     * 流程结束后统一落库。
     *
     * <p>用户消息已由 {@code ContextLoadNode} 提前写入（保证提问不丢），
     * 这里补上助手回复；若本轮走到兜底，再把问题投进低置信度问题池，
     * 作为后续补充知识库的依据。
     *
     * <p>落库失败只记日志不影响返回：回复已经生成好了，
     * 因为写库失败而让用户白等一轮是本末倒置。
     *
     * @param context 对话上下文
     */
    private void persist(ChatContext context) {
        try {
            chatMemoryService.saveAssistantReply(context);
        } catch (Exception e) {
            log.error("助手回复落库失败：session={}", context.getSessionId(), e);
        }

        // 用 finalState 而不是 state：流程结束后 state 已经是 END，
        // 拿它判断"是否兜底"会永远不成立，低置信度问题就永远进不了池
        if (context.getFinalState() != ChatState.FALLBACK) {
            return;
        }

        try {
            lowConfidenceQuestionService.record(
                    context.getUserMessage(), context.effectiveConfidence(), context.getSessionId());
        } catch (Exception e) {
            log.error("低置信度问题入池失败：session={}", context.getSessionId(), e);
        }

        createHandoffTicket(context);
    }

    /**
     * 兜底时创建人工工单。
     *
     * <p><b>为什么建单放在调度器而不是兜底节点里</b>：节点约定是"只做决策与计算、
     * 不在节点内持久化"，持久化统一在流程首尾处理。这样"一次对话到底写了哪些数据"
     * 在一个方法里就能看全，排查"数据为什么没落库"不用翻遍所有节点。
     *
     * <p>建单失败只记日志、不影响返回：用户已经拿到"已转人工"的答复，
     * 因为写库失败再抛异常，只会把一次已经收尾的对话变成 500。
     * 但这里用 error 级别 —— 单子没建起来意味着<b>这条问题可能没人跟进</b>，必须能被告警发现。
     *
     * @param context 对话上下文
     */
    private void createHandoffTicket(ChatContext context) {
        if (!agentProperties.isAutoCreateTicket()) {
            log.debug("自动建单已关闭，跳过：session={}", context.getSessionId());
            return;
        }
        try {
            ticketService.createFallbackTicket(context.getSessionId(), context.getUserId(),
                    context.getUserMessage(), context.effectiveConfidence());
        } catch (Exception e) {
            log.error("兜底建单失败，该问题可能无人跟进：session={}", context.getSessionId(), e);
        }
    }

    /* ==================== 装配与校验 ==================== */

    /**
     * 构建状态到节点的映射，并校验状态机完整性。
     *
     * <p>校验放在构造阶段是刻意的：节点漏实现、或两个节点声明了同一个状态，
     * 都属于"编排配置错误"，应该在应用启动时就报出来，
     * 而不是等到某个特定状态的请求打进来才暴露。
     *
     * @param nodes Spring 收集到的全部节点
     * @return 不可变的状态 → 节点映射
     * @throws IllegalStateException 校验不通过时抛出
     */
    private Map<ChatState, ChatNode> buildNodeMap(List<ChatNode> nodes) {
        EnumMap<ChatState, ChatNode> map = new EnumMap<>(ChatState.class);
        for (ChatNode node : nodes) {
            ChatNode previous = map.put(node.state(), node);
            if (previous != null) {
                throw new IllegalStateException("状态 " + node.state() + " 存在多个处理节点："
                        + previous.getClass().getName() + " 与 " + node.getClass().getName()
                        + "，请确保一个状态只由一个节点负责");
            }
        }

        List<String> missing = new ArrayList<>();
        for (ChatState state : ChatState.values()) {
            if (!state.isTerminal() && !map.containsKey(state)) {
                missing.add(state.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("以下状态没有对应的节点实现：" + missing
                    + "，请确认对应的 ChatNode 实现类已被 Spring 扫描到");
        }

        log.info("对话状态机装配完成，共 {} 个节点", map.size());
        return Collections.unmodifiableMap(map);
    }

    /**
     * 校验入参。
     *
     * @param sessionId   会话业务ID
     * @param userMessage 用户输入
     */
    private void validateInput(String sessionId, String userMessage) {
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "sessionId 不能为空");
        }
        if (!StringUtils.hasText(userMessage)) {
            throw new BizException(ResultCode.PARAM_INVALID, "消息内容不能为空");
        }
        if (userMessage.length() > ChatConstants.MAX_MESSAGE_LENGTH) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "消息长度超过上限 " + ChatConstants.MAX_MESSAGE_LENGTH + " 字");
        }
    }

    /**
     * 由上下文构造对外返回结果。
     *
     * @param context 对话上下文
     * @return 处理结果
     */
    private ChatReply buildReply(ChatContext context) {
        return ChatReply.builder()
                .sessionId(context.getSessionId())
                .messageId(context.getAssistantMessageId())
                .content(context.getReplyText())
                .intent(context.getIntent())
                .confidence(context.effectiveConfidence())
                .citations(List.copyOf(context.getRetrievedChunks()))
                .handoffRequired(context.isHandoffRequired())
                .finalState(context.getFinalState())
                .visitedStates(List.copyOf(context.getVisitedStates()))
                .costMs(context.getCostMs())
                .promptTokens(context.getPromptTokens())
                .completionTokens(context.getCompletionTokens())
                .totalTokens(context.getTotalTokens())
                .build();
    }
}
