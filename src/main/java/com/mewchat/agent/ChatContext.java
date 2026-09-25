package com.mewchat.agent;

import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.tool.ToolResult;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话上下文。
 *
 * <p>一次 {@code ChatSupervisor.process()} 调用的<b>全部中间状态与结果</b>都装在这里，
 * 各节点从它读取输入、把计算结果写回它。把它设计成可变对象是有意的：
 * 状态机逐个节点推进，天然是"在同一个上下文上累积"的过程，
 * 若强制不可变，每个节点都要返回"新状态 + 新上下文"，反而更难追踪。
 *
 * <p>字段按流程顺序分三组：输入（会话与记忆）、中间决策（意图、参数、检索结果）、
 * 输出（回复文本与统计）。排查问题时把整个对象打进日志即可还原一次对话的全貌。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
public class ChatContext {

    /* ==================== 输入：会话标识 ==================== */

    /** 会话业务ID */
    private final String sessionId;

    /** 用户ID，游客会话为空 */
    private final Long userId;

    /** 用户本轮原始输入 */
    private final String userMessage;

    /* ==================== 输入：记忆 ==================== */

    /** 短时记忆：滑动窗口内的最近若干轮对话（不含本轮） */
    @Builder.Default
    private List<HistoryTurn> history = new ArrayList<>();

    /** 长时记忆：会话摘要，仅在历史被窗口截断后用于补足更早的上下文 */
    private String summary;

    /** 指代消解后的输入，把"它""那单"等补全为具体实体 */
    private String resolvedMessage;

    /** 查询改写后的检索用文本 */
    private String rewrittenQuery;

    /* ==================== 中间决策 ==================== */

    /** 识别出的意图 */
    @Builder.Default
    private IntentType intent = IntentType.UNKNOWN;

    /** 意图识别的置信度，0~1 */
    private BigDecimal intentConfidence;

    /** 从用户输入中抽取的参数，如 orderNo */
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();

    /** 当前状态（流程游标） */
    @Builder.Default
    private ChatState state = ChatState.CONTEXT_LOAD;

    /**
     * 产出本轮答案的状态，即 REPLY / CLARIFY / FALLBACK / REJECT 四者之一。
     *
     * <p>必须与 {@link #state} 分开：{@code state} 是流程游标，
     * 流程走完必然停在 {@code END}，用它判断"本轮是回复还是兜底"永远得到 END。
     * 本字段由调度器在节点返回终态时记录下"那个节点所属的状态"，
     * 对外表达本轮结果、以及判定是否兜底（决定要不要写入低置信度问题池、建工单）都用它。
     *
     * <p>四者的语义区别很关键：{@code REPLY} 正常作答、{@code CLARIFY} 需要用户补信息、
     * {@code FALLBACK} 答不上来（转人工）、{@code REJECT} 命中安全护栏而拒答。
     * 尤其后两者不能混：拒答是"我不回答这类内容"，不该建工单、也不该说"系统答不上来"。
     */
    @Builder.Default
    private ChatState finalState = ChatState.CONTEXT_LOAD;

    /** 已走过的状态轨迹，用于日志与链路展示，也是排查流程问题最直接的依据 */
    @Builder.Default
    private List<ChatState> visitedStates = new ArrayList<>();

    /* ==================== 中间决策：处理结果 ==================== */

    /**
     * 本轮实际处理该问题的专家节点名（如 RagSpecialist、ToolSpecialist）。
     *
     * <p>由处理节点设置，最终落到 {@code message.agent_name}，
     * 用于统计"哪类问题由谁处理、效果如何"。
     */
    private String handlerAgent;

    /** RAG 检索到的知识切片 */
    @Builder.Default
    private List<RetrievedChunk> retrievedChunks = new ArrayList<>();

    /**
     * 本轮是否已经做过一次补充检索。
     *
     * <p>置信度落在中档区间时，置信度校验节点会置位本标记并要求再检索一次，
     * 检索节点据此放大召回范围（见 {@code mewchat.rag.retry-top-k-multiplier}）。
     *
     * <p>它同时是"只补检索一次"的判据：置位后仍不达标就直接兜底，
     * 而不是继续放宽标准 —— 反复放宽到满意为止，等于把置信度的意义抹掉。
     * 用显式标记而不是去翻状态轨迹猜"检索过几次"：轨迹是给人看的，
     * 拿它当控制条件会让流程逻辑藏在历史里，改动时极易被忽略。
     */
    private boolean retrievalRetry;

    /** 工具调用结果 */
    private ToolResult toolResult;

    /**
     * 本轮回答的置信度，由处理节点写入。
     *
     * <p>知识类取召回片段的最优得分；工具类成功为 1、失败为 0。
     */
    private BigDecimal answerConfidence;

    /**
     * 追问提示。
     *
     * <p>非空表示"信息不足、应该反问用户"。由处理节点在任何发现缺口的地方设置
     * （例如工具发现缺订单号），置信度校验读到它就转追问分支。
     */
    private String clarificationHint;

    /* ==================== 输出 ==================== */

    /** 最终回复文本 */
    private String replyText;

    /** 是否需要转人工（兜底分支会置为 true） */
    private boolean handoffRequired;

    /** 本轮失败原因，仅供内部记录，不返回给用户 */
    private String errorMessage;

    /**
     * 流式输出回调，非流式调用时为 null。
     *
     * <p>它描述的不是"本轮对话的状态"，而是"本轮结果往哪里送"这一交付方式，
     * 因此既不参与持久化、也不进日志。放在上下文里是因为回复节点要用它 ——
     * 节点之间只通过上下文交换信息，不必为此再加一层参数透传。
     *
     * <p>是否为空是"走流式还是走同步"的唯一判据（见
     * {@link com.mewchat.agent.supervisor.node.ReplyNode}）。
     */
    private ChatStreamListener streamListener;

    /* ==================== 输出：统计 ==================== */

    /** 本轮使用的模型名 */
    private String modelName;

    /** 输入 token 数 */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 本轮总耗时（毫秒） */
    private Long costMs;

    /**
     * 本轮助手回复落库后的消息ID。
     *
     * <p>由 {@code persist} 阶段回填，用于让 {@code done} 事件带上它 ——
     * 前端点赞/点踩要指出"给哪条消息反馈"，而这个ID只有落库之后才存在。
     * 不填也照样能跑（反馈接口会报"消息不存在"），因此落库失败时不阻断本轮。
     */
    private Long assistantMessageId;

    /* ==================== 行为方法 ==================== */

    /**
     * 记录一个已走过的状态。
     *
     * @param visited 状态
     */
    public void markVisited(ChatState visited) {
        this.state = visited;
        this.visitedStates.add(visited);
    }

    /**
     * 取用于检索/工具调用的有效查询文本。
     *
     * <p>优先级：指代消解结果 → 查询改写结果 → 用户原始输入。
     * 逐级回退保证任何一步没做（如指代消解被跳过）时流程都不会拿到空文本。
     *
     * @return 非空查询文本
     */
    public String effectiveQuery() {
        if (StringUtils.hasText(resolvedMessage)) {
            return resolvedMessage;
        }
        if (StringUtils.hasText(rewrittenQuery)) {
            return rewrittenQuery;
        }
        return userMessage == null ? "" : userMessage;
    }

    /**
     * 计算本轮的有效置信度：取意图置信度与回答置信度的<b>较小值</b>。
     *
     * <p>为什么取较小值：如果连用户想问什么都没识别准，哪怕检索得分很高也不该当成本轮可信。
     * 两个信号都缺失时返回 0，宁可走兜底也不冒险给出可能错误的答案。
     *
     * @return 0~1 之间的置信度
     */
    public BigDecimal effectiveConfidence() {
        BigDecimal intentScore = intentConfidence == null ? BigDecimal.ZERO : intentConfidence;
        BigDecimal answerScore = answerConfidence == null ? BigDecimal.ZERO : answerConfidence;
        return intentScore.min(answerScore);
    }

    /**
     * 是否召回到了知识片段。
     *
     * @return true 表示有召回结果
     */
    public boolean hasCitations() {
        return !CollectionUtils.isEmpty(retrievedChunks);
    }

    /**
     * 判断工具调用是否因缺少参数而需要追问。
     *
     * @return true 表示应转追问分支
     */
    public boolean toolNeedsClarification() {
        return toolResult != null && toolResult.needsClarification();
    }
}
