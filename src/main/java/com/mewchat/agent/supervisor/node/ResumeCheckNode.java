package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.config.AgentProperties;
import com.mewchat.dao.mysql.entity.PendingClarification;
import com.mewchat.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ChatState#RESUME_CHECK} 节点：判断本轮输入是不是在回答上一次的追问。
 *
 * <p><b>为什么需要它</b>：上一轮如果问的是"请从下面的订单里选一个"，
 * 用户会回答"1"。这个"1"不是一句用户诉求，拿它去做意图识别只会得到
 * {@link IntentType#UNKNOWN}（模型面对一个孤零零的数字无从判断），
 * 于是用户刚做完的选择被当成一句听不懂的话、再被反问一次。
 * 本节点的作用就是把这个序号还原成具体的订单号，
 * <b>然后直接进工具层，跳过意图识别与路由</b> —— 这就是"从断点继续"。
 *
 * <p><b>只接受"序号式"回答，不匹配"消息里含订单号"</b>：这一点是刻意的。
 * 用户如果把订单号原样打出来（"MC202409240001"），走<b>正常流程</b>本来就能被
 * 意图识别正确抽取成 orderNo —— 换句话说序号是唯一"正常流程处理不了"的输入，
 * 也只有它需要续接。反之，若把"消息里含订单号"也当作选中，
 * 用户接着问"MC202409240001 这个订单能退吗"就会被误判成"他选了第一个订单"，
 * 直接去查物流状态，答非所问。
 *
 * @author MewChat
 */
@Component
public class ResumeCheckNode implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(ResumeCheckNode.class);

    /** 阿拉伯数字序号："1"、"1)"、"1."、"第1个" */
    private static final Pattern ARABIC_ORDINAL = Pattern.compile("^\\s*第?\\s*(\\d{1,2})\\s*[个)）、.．]?\\s*$");

    /** 中文序号："一"、"第一个"、"选三" 这类写法里的单个数词 */
    private static final Pattern CHINESE_ORDINAL = Pattern.compile("^\\s*(?:第|选)?\\s*([一二三四五六七八九十])\\s*个?\\s*[)）、.．]?\\s*$");

    /** 中文数词到数字的映射 */
    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
            "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);

    private final ConversationService conversationService;

    private final AgentProperties agentProperties;

    public ResumeCheckNode(ConversationService conversationService, AgentProperties agentProperties) {
        this.conversationService = conversationService;
        this.agentProperties = agentProperties;
    }

    @Override
    public ChatState state() {
        return ChatState.RESUME_CHECK;
    }

    @Override
    public ChatState execute(ChatContext context) {
        PendingClarification pending = loadValidPending(context);
        if (pending == null) {
            return ChatState.INTENT_RECOGNIZE;
        }

        Optional<String> selected = parseSelection(pending, context.getUserMessage());
        if (selected.isEmpty()) {
            // 用户没有在回答那个追问（去问别的事了）：清掉挂起状态，按新问题走。
            // 不清的话，他之后随口回一个"1"会被续接回这个早已过时的追问
            clearPending(context, "用户未选择候选项");
            return ChatState.INTENT_RECOGNIZE;
        }

        IntentType intent = IntentType.parse(pending.getIntent());
        if (intent == IntentType.UNKNOWN) {
            // 挂起状态里的意图名解析不出来（历史数据、或枚举改过名）：
            // 硬续接会让工具层因为"意图未登记工具"而失败，不如当作新问题重走一遍
            clearPending(context, "挂起状态里的意图无法解析");
            return ChatState.INTENT_RECOGNIZE;
        }

        applyResume(context, pending, intent, selected.get());
        clearPending(context, "已续接");
        log.info("澄清续接成功：session={} 意图={} 选中={} 跳过意图识别",
                context.getSessionId(), intent, selected.get());
        return ChatState.TOOL_CALL;
    }

    /* ==================== 挂起状态读取 ==================== */

    /**
     * 读取仍有效的挂起状态。
     *
     * <p>任何异常都当作"没有挂起项"：读不到挂起状态只是不能续接，
     * 按新问题走一遍流程仍然能得到正确结果，不该让整轮对话失败。
     *
     * @param context 对话上下文
     * @return 挂起状态；没有、已过期或读取失败时返回 null
     */
    private PendingClarification loadValidPending(ChatContext context) {
        PendingClarification pending;
        try {
            pending = conversationService.getPendingClarification(context.getSessionId());
        } catch (Exception e) {
            log.error("读取挂起澄清状态失败，按新问题处理：session={}", context.getSessionId(), e);
            return null;
        }

        if (pending == null) {
            return null;
        }
        if (isExpired(pending)) {
            // 过期即视为失效：半天之后随口回一个"1"，不该被续接回那个早已过时的追问
            log.debug("挂起澄清已过期，按新问题处理：session={} 挂起于={}",
                    context.getSessionId(), pending.getCreatedAt());
            clearPending(context, "挂起状态已过期");
            return null;
        }
        return pending;
    }

    /**
     * 判断挂起状态是否已过期。
     *
     * @param pending 挂起状态
     * @return true 表示已过期
     */
    private boolean isExpired(PendingClarification pending) {
        if (pending.getCreatedAt() == null) {
            // 没有时间戳（历史数据）时按"不过期"处理：宁可能续接，也不要凭空作废
            return false;
        }
        long ttlMinutes = Math.max(1, agentProperties.getClarifyPendingTtlMinutes());
        return pending.getCreatedAt().plusMinutes(ttlMinutes).isBefore(LocalDateTime.now());
    }

    /**
     * 清除挂起状态。
     *
     * <p>失败只记日志：状态没清掉最多导致下一次也可能被续接，不影响本轮结果。
     *
     * @param context 对话上下文
     * @param reason  清除原因，仅用于日志
     */
    private void clearPending(ChatContext context, String reason) {
        try {
            conversationService.clearPendingClarification(context.getSessionId());
            log.debug("清除挂起澄清状态：session={} 原因={}", context.getSessionId(), reason);
        } catch (Exception e) {
            log.error("清除挂起澄清状态失败：session={} 原因={}", context.getSessionId(), reason, e);
        }
    }

    /* ==================== 选择解析 ==================== */

    /**
     * 从用户输入里解出他选中的候选值。
     *
     * @param pending 挂起状态
     * @param message 用户输入
     * @return 选中的候选值；不是序号式回答时为空
     */
    private Optional<String> parseSelection(PendingClarification pending, String message) {
        List<PendingClarification.Option> options = pending.getOptions();
        if (CollectionUtils.isEmpty(options) || !StringUtils.hasText(message)) {
            return Optional.empty();
        }

        String text = message.trim();
        int index = parseOrdinal(text);
        if (index < 1 || index > options.size()) {
            // 序号越界按"没选中"处理：用户可能只是说了个别的数字，
            // 猜一个最近的候选出来比老实走一遍新流程更危险
            return Optional.empty();
        }

        String value = options.get(index - 1).getValue();
        return StringUtils.hasText(value) ? Optional.of(value) : Optional.empty();
    }

    /**
     * 把"序号式"输入解析成 1 起的下标。
     *
     * @param text 用户输入
     * @return 下标；不是序号式输入时返回 -1
     */
    private int parseOrdinal(String text) {
        Matcher arabic = ARABIC_ORDINAL.matcher(text);
        if (arabic.matches()) {
            try {
                return Integer.parseInt(arabic.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        Matcher chinese = CHINESE_ORDINAL.matcher(text);
        if (chinese.matches()) {
            Integer number = CHINESE_NUMBERS.get(chinese.group(1));
            return number == null ? -1 : number;
        }
        return -1;
    }

    /* ==================== 续接 ==================== */

    /**
     * 把"用户选中的候选项"还原成一次正常的工具调用所需的状态。
     *
     * @param context 对话上下文
     * @param pending 挂起状态
     * @param intent  上一轮识别出的意图
     * @param value   用户选中的候选值
     */
    private void applyResume(ChatContext context, PendingClarification pending,
                            IntentType intent, String value) {
        context.setIntent(intent);
        // 意图是上一轮已经识别过的，用户这次只是确认了候选项，因此对它有十足把握。
        // 不给满分的话，有效置信度会被这一项压低（effective = min(意图, 回答)），
        // 于是"明明查到了订单"却因为意图置信度缺失被判低置信度、走了兜底
        context.setIntentConfidence(BigDecimal.ONE);

        context.getParams().put(pending.getMissingParam(), value);

        // 必须覆盖"指代消解"的结果：它会把这个"1"原样返回，
        // 而 effectiveQuery() 优先取它 —— 不覆盖的话，回复节点看到的用户问题就是"1"，
        // 生成出来的回答必然答非所问
        String resumeQuery = buildResumeQuery(pending, value);
        context.setResolvedMessage(resumeQuery);
        context.setRewrittenQuery(resumeQuery);
    }

    /**
     * 拼出一句能被模型理解的完整问题。
     *
     * @param pending 挂起状态
     * @param value   用户选中的候选值
     * @return 改写后的查询文本
     */
    private String buildResumeQuery(PendingClarification pending, String value) {
        String origin = StringUtils.hasText(pending.getQuestion()) ? pending.getQuestion() : "查询订单";
        return "用户从候选中选择了订单 " + value + "（此前的问题：" + origin + "）";
    }
}
