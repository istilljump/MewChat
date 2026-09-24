package com.mewchat.agent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 对话流程状态。
 *
 * <p><b>这是整个 Agent 编排的骨架</b>。流程推进由本枚举声明的转移关系驱动，
 * 而不是让大模型自由决定下一步 —— 大模型只负责"识别意图""生成文本"这类
 * 单点判断，<b>流程的走向由代码控制</b>，这样才可预测、可测试、出问题可定位。
 *
 * <p>完整流转图（箭头即 {@link #nextStates()} 声明的合法转移）：
 * <pre>
 *   CONTEXT_LOAD ──▶ GUARD_CHECK ──▶（违规）──▶ REJECT ──▶ END
 *                        │
 *                        ▼
 *                  RESUME_CHECK ──▶（用户选了候选）────────────────┐
 *                        │                                        │
 *                        ▼                                        │
 *                  INTENT_RECOGNIZE ──▶ ROUTE                    │
 *                                          │                      │
 *             ┌────────────────────────────┼──────────────┐       │
 *             ▼                            ▼              ▼       │
 *       RAG_RETRIEVE                  TOOL_CALL      CLARIFY      │
 *             │                            │              │       │
 *             └────────────┬───────────────┘              │       │
 *                          ▼                              │       │
 *                  CONFIDENCE_CHECK ─▶（追问）────────────┤       │
 *                     │  ▲                                │       │
 *        （0.4~0.7）──┘  └──二次检索───────────┐          │       │
 *                     │                       │          │       │
 *            ┌────────┴────────┐              │          │       │
 *            ▼                 ▼              │          ▼       │
 *          REPLY           FALLBACK           └──────────┘       │
 *            │  ▲              │                                 │
 *            │  └──生成失败────┘                                 │
 *            │                 │                                 │
 *            └────────┬────────┘                                 │
 *                     ▼                                          │
 *                    END  ◀──────────────────────────────────────┘
 * </pre>
 *
 * <p>每次转移都会经 {@link #canTransitionTo(ChatState)} 校验，
 * 节点若返回了未声明的后继状态会立即抛异常，把编排错误暴露在开发阶段，
 * 而不是变成线上一次莫名其妙的回答。
 *
 * @author MewChat
 */
public enum ChatState {

    /** 加载会话上下文：会话记录、短时记忆（最近若干轮）、长时记忆（摘要） */
    CONTEXT_LOAD("加载会话上下文"),

    /**
     * 安全护栏校验：敏感词等违规输入的拦截。
     *
     * <p>放在意图识别<b>之前</b>：违规内容不该再送给大模型去理解一遍，
     * 既省一次调用，也避免模型顺着违规话题往下答。
     * 放在上下文加载<b>之后</b>：这样违规消息已经落库，留有审计痕迹。
     */
    GUARD_CHECK("安全护栏校验"),

    /**
     * 澄清续接判断：判断本轮输入是不是在回答上一次的追问。
     *
     * <p>只有当上一轮留下了"挂起的澄清"时才有意义（比如问了"请选择订单"，
     * 用户回了一个序号）。它不是新问题，因此不能走意图识别 ——
     * 拿"1"去做意图识别，模型只会给出一个 UNKNOWN。
     */
    RESUME_CHECK("澄清续接判断"),

    /** 意图识别 + 查询改写 + 参数抽取（调用大模型，但结果只作为决策输入） */
    INTENT_RECOGNIZE("意图识别与查询改写"),

    /** 按意图路由到对应的处理节点 */
    ROUTE("按意图路由"),

    /**
     * 知识检索：走 RAG，召回知识切片。
     *
     * <p>本状态可能被进入<b>两次</b>：置信度落在中档（见 {@link #CONFIDENCE_CHECK}）时，
     * 会回到这里做一次放宽条件的补充检索。
     */
    RAG_RETRIEVE("知识检索"),

    /** 业务工具调用：订单、物流等确定性查询 */
    TOOL_CALL("业务工具调用"),

    /**
     * 置信度校验：判断本轮回答是否可靠，决定回复、补充检索、追问还是兜底。
     *
     * <p>三档闸值：达标直接回复、中档补一次检索、不达标兜底（转人工 + 建工单）。
     */
    CONFIDENCE_CHECK("置信度校验"),

    /** 追问澄清：信息不足或意图不明时反问用户（可能附带候选选项） */
    CLARIFY("追问澄清"),

    /** 拒答：输入命中安全护栏，直接拒绝并给出固定的合规回复 */
    REJECT("拒答"),

    /** 生成回复 */
    REPLY("生成回复"),

    /** 兜底：知识库与工具都给不出可靠答案时的降级处理（转人工 + 建工单） */
    FALLBACK("兜底处理"),

    /** 流程结束 */
    END("结束");

    private static final Map<ChatState, Set<ChatState>> TRANSITIONS = new EnumMap<>(ChatState.class);

    /*
     * 转移表必须放在静态块里而不是枚举构造参数中：
     * Java 不允许在枚举常量的构造参数里引用同一枚举中尚未初始化的其他常量
     * （编译报"非法前向引用"）。静态块在所有常量初始化完成之后执行。
     */
    static {
        // 入口先过护栏：违规输入在意图识别之前就被拦下，不送给模型
        TRANSITIONS.put(CONTEXT_LOAD, EnumSet.of(GUARD_CHECK));
        TRANSITIONS.put(GUARD_CHECK, EnumSet.of(RESUME_CHECK, REJECT));
        // 澄清续接的两个出口：命中候选就直接进工具层（跳过意图识别与路由），否则按新问题走
        TRANSITIONS.put(RESUME_CHECK, EnumSet.of(INTENT_RECOGNIZE, TOOL_CALL));
        TRANSITIONS.put(INTENT_RECOGNIZE, EnumSet.of(ROUTE));
        // 路由的三个出口：知识类走 RAG、业务类走工具、识别不出则追问
        TRANSITIONS.put(ROUTE, EnumSet.of(RAG_RETRIEVE, TOOL_CALL, CLARIFY));
        TRANSITIONS.put(RAG_RETRIEVE, EnumSet.of(CONFIDENCE_CHECK));
        TRANSITIONS.put(TOOL_CALL, EnumSet.of(CONFIDENCE_CHECK));
        // 校验后四种去向：达标回复、中档补检索、可追问就问、都不行则兜底。
        // 回到 RAG_RETRIEVE 构成一个环，但被两件事夹住：置信度校验只允许补检索一次
        // （判据是轨迹里是否已经出现过一次检索），外层还有 max-steps 兜住整个流程
        TRANSITIONS.put(CONFIDENCE_CHECK, EnumSet.of(REPLY, FALLBACK, CLARIFY, RAG_RETRIEVE));
        // 追问/拒答/回复/兜底都是一次交互的终点：要返回给用户的内容已经确定。
        // REPLY 额外允许转到 FALLBACK：文本生成本身失败（模型限流、超时）时，
        // 应该降级为兜底话术，而不是把异常抛给用户
        TRANSITIONS.put(CLARIFY, EnumSet.of(END));
        TRANSITIONS.put(REJECT, EnumSet.of(END));
        TRANSITIONS.put(REPLY, EnumSet.of(END, FALLBACK));
        TRANSITIONS.put(FALLBACK, EnumSet.of(END));
        TRANSITIONS.put(END, EnumSet.noneOf(ChatState.class));
    }

    /** 中文说明，用于日志与链路追踪展示 */
    private final String label;

    ChatState(String label) {
        this.label = label;
    }

    /**
     * 本状态允许转移到的后继状态。
     *
     * @return 后继状态集合，终态返回空集
     */
    public Set<ChatState> nextStates() {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(this, EnumSet.noneOf(ChatState.class)));
    }

    /**
     * 判断从当前状态转移到目标状态是否合法。
     *
     * @param next 目标状态
     * @return true 表示该转移已声明
     */
    public boolean canTransitionTo(ChatState next) {
        return next != null && nextStates().contains(next);
    }

    /**
     * 是否为终态。
     *
     * @return true 表示流程到此结束
     */
    public boolean isTerminal() {
        return this == END;
    }

    public String getLabel() {
        return label;
    }
}
