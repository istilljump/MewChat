package com.mewchat.agent.specialist;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatNode;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.dao.mysql.entity.PendingClarification;
import com.mewchat.service.ConversationService;
import com.mewchat.tool.ClarificationOption;
import com.mewchat.tool.ToolInvoker;
import com.mewchat.tool.ToolResult;
import com.mewchat.tool.logistics.LogisticsTool;
import com.mewchat.tool.order.OrderTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具专家：负责订单、物流这类"确定性查询"。
 *
 * <p>与 RAG 专家的分工：知识类问题答案在文档里、靠语义检索；
 * 订单物流类问题的答案在业务库里、必须按单号精确查询。
 * 拿语义检索去答"我的订单到哪了"是不可能答对的，所以两条路必须在路由处分流。
 *
 * <p><b>为什么参数是工具名而不是意图</b>：工具层不认意图，
 * "意图 → 工具名"的映射由本节点（编排侧）负责，保持分层单向。
 * 映射里的工具名直接引用工具类上的常量，不做字符串字面量 ——
 * 手写 {@code "order_query"} 一旦拼错，表现为"调用时报未注册的工具"，
 * 引用常量则编译期就不可能出现这种错配。
 *
 * <p><b>缺失参数有两道校验，不是重复而是分工不同</b>：
 * <ul>
 *     <li>这里的校验依据是 {@link IntentType#getRequiredParams()}，语义是"这些参数全都必需"，
 *         作用是<b>在发起调用之前</b>拦掉拿空参数去查库这种必然白跑的请求</li>
 *     <li>工具自己还会再校验一次，因为有些约束这里表达不了：物流查询是
 *         "运单号或订单号<b>二者取一</b>"，不是"两个都必需"</li>
 * </ul>
 *
 * <p><b>追问不只是"问一句"</b>：能列出候选时会把候选一并给出（"请从下面的订单里选一个"），
 * 并把这次追问<b>挂起</b>到会话上 —— 用户下一轮回一个序号，才能被还原成具体订单号
 * （见 {@code ResumeCheckNode}）。挂起状态的写入放在这里，
 * 是因为"要不要列候选、列哪些"本身就是本节点的决策；写库则是通过 service 完成。
 *
 * <p>对 {@link ToolInvoker} 采用 {@link ObjectProvider} 软依赖：
 * 工具层未装配时应用仍能启动，本节点按"工具不可用"处理、流程照常走到置信度校验并降级。
 *
 * @author MewChat
 */
@Component
public class ToolSpecialist implements ChatNode {

    private static final Logger log = LoggerFactory.getLogger(ToolSpecialist.class);

    /**
     * 意图到工具名的映射。新增业务工具时在这里加一行。
     *
     * <p>注意没有退款政策的映射：{@link IntentType#REFUND_ASK} 的路由目标是知识检索，
     * 平台政策原文按语义检索更合适，按类目查表的能力（{@code RefundTool}）
     * 供"类目明确"的问法使用，改由这里接入只需加一行。
     */
    private static final Map<IntentType, String> INTENT_TOOL_MAP = Map.of(
            IntentType.ORDER_QUERY, OrderTool.NAME,
            IntentType.LOGISTICS_QUERY, LogisticsTool.NAME
    );

    /** 参数名到中文说明的映射，用于生成追问话术 */
    private static final Map<String, String> PARAM_LABELS = Map.of(
            "orderNo", "订单号",
            "trackingNo", "运单号",
            "phone", "下单手机号",
            "category", "商品类目"
    );

    private final ObjectProvider<ToolInvoker> toolInvokerProvider;

    private final ConversationService conversationService;

    public ToolSpecialist(ObjectProvider<ToolInvoker> toolInvokerProvider,
                          ConversationService conversationService) {
        this.toolInvokerProvider = toolInvokerProvider;
        this.conversationService = conversationService;
    }

    @Override
    public ChatState state() {
        return ChatState.TOOL_CALL;
    }

    @Override
    public ChatState execute(ChatContext context) {
        context.setHandlerAgent(getClass().getSimpleName());

        String toolName = INTENT_TOOL_MAP.get(context.getIntent());
        if (toolName == null) {
            // 路由到了本节点却没登记工具，属于配置遗漏，按失败处理走兜底
            log.error("意图 {} 没有登记对应的工具：session={}", context.getIntent(), context.getSessionId());
            context.setErrorMessage("意图 " + context.getIntent() + " 未配置对应工具");
            context.setAnswerConfidence(BigDecimal.ZERO);
            return ChatState.CONFIDENCE_CHECK;
        }

        // 取一次即可：后面的"列候选"与"调工具"用的是同一个入口
        ToolInvoker toolInvoker = toolInvokerProvider.getIfAvailable();

        List<String> missing = findMissingParams(context);
        if (!missing.isEmpty()) {
            context.setToolResult(ToolResult.needMoreInfo(toolName, missing));
            context.setClarificationHint(
                    buildClarification(context, toolInvoker, toolName, missing, null));
            context.setAnswerConfidence(BigDecimal.ZERO);
            log.debug("缺少必要参数，转追问：session={} missing={}", context.getSessionId(), missing);
            return ChatState.CONFIDENCE_CHECK;
        }

        if (toolInvoker == null) {
            log.warn("工具服务不可用，本轮按调用失败处理：session={} tool={}",
                    context.getSessionId(), toolName);
            context.setToolResult(ToolResult.fail(toolName, "工具服务不可用"));
            context.setAnswerConfidence(BigDecimal.ZERO);
            return ChatState.CONFIDENCE_CHECK;
        }

        ToolResult result;
        try {
            result = toolInvoker.invoke(toolName, context.getParams());
        } catch (Exception e) {
            log.error("工具调用异常：session={} tool={}", context.getSessionId(), toolName, e);
            context.setErrorMessage("工具调用异常：" + e.getMessage());
            context.setAnswerConfidence(BigDecimal.ZERO);
            return ChatState.CONFIDENCE_CHECK;
        }

        if (result == null) {
            // 接口约定不允许返回 null，真出现了也不能让它变成 NPE
            result = ToolResult.fail(toolName, "工具返回空结果");
        }
        context.setToolResult(result);

        if (result.needsClarification()) {
            context.setClarificationHint(buildClarification(
                    context, toolInvoker, toolName, result.getMissingParams(), result.getClarifyHint()));
            context.setAnswerConfidence(BigDecimal.ZERO);
            return ChatState.CONFIDENCE_CHECK;
        }

        // 工具是确定性查询：查到就是 1，没查到就是 0，不存在"比较像"的中间态
        context.setAnswerConfidence(result.isSuccess() ? BigDecimal.ONE : BigDecimal.ZERO);
        log.debug("工具调用完成：session={} tool={} success={}",
                context.getSessionId(), toolName, result.isSuccess());
        return ChatState.CONFIDENCE_CHECK;
    }

    /* ==================== 追问 ==================== */

    /**
     * 生成追问话术，并决定是否列候选、是否挂起本轮追问。
     *
     * <p>优先顺序体现了"哪种追问更好用"：
     * <ol>
     *     <li><b>能列出候选</b> → 列出来并记下挂起状态。用户记不住订单号，
     *         但看到自己的订单列表就能一眼认出来，回答一个序号即可</li>
     *     <li><b>工具自己拟了话术</b> → 用它。"没查到订单号 X，麻烦核对"比
     *         "请提供订单号"贴合实际，而只有工具知道自己拿什么去查、查到了什么</li>
     *     <li><b>都没有</b> → 按缺失的参数名生成"麻烦提供一下 X"</li>
     * </ol>
     *
     * @param context       对话上下文
     * @param invoker       工具入口，可为 null（工具层未装配）
     * @param toolName      工具名
     * @param missingParams 缺失的参数名，可能为空（例如"记录不存在"这类追问）
     * @param toolHint      工具拟的追问话术，可为 null
     * @return 追问文本，保证非空
     */
    private String buildClarification(ChatContext context, ToolInvoker invoker, String toolName,
                                      List<String> missingParams, String toolHint) {
        List<ClarificationOption> options = (invoker == null || missingParams.isEmpty())
                ? List.of()
                : invoker.listOptions(toolName, context.getUserId());

        if (options.isEmpty()) {
            return StringUtils.hasText(toolHint) ? toolHint : buildClarifyMessage(missingParams);
        }

        String targetParam = resolveTargetParam(missingParams);
        savePendingClarification(context, targetParam, options);
        log.debug("追问附带 {} 个候选项，已挂起等待用户选择：session={} 目标参数={}",
                options.size(), context.getSessionId(), targetParam);
        return buildOptionHint(options);
    }

    /**
     * 解析候选项应当填进哪个参数。
     *
     * <p>目前唯一的候选来源是订单（{@link OrderTool#listOptions(Long)}），
     * 候选值就是订单号，因此统一填 {@code orderNo}。物流查询虽然报的是"缺运单号"，
     * 但它接受订单号并自行反查运单号（见 {@link LogisticsTool#invoke}），
     * 所以填 {@code orderNo} 是安全的；反过来把订单号塞进 {@code trackingNo}
     * 则是名不副实的。
     *
     * <p>将来若出现别的候选来源（例如让用户选"是哪件商品"），
     * 这个映射必须由提供候选的工具自己声明，不能继续在这里猜。
     *
     * @param missingParams 缺失的参数名
     * @return 目标参数名
     */
    private static String resolveTargetParam(List<String> missingParams) {
        if (missingParams.contains("orderNo") || missingParams.contains("trackingNo")) {
            return "orderNo";
        }
        return missingParams.get(0);
    }

    /**
     * 生成带候选列表的追问话术。
     *
     * <p>明确写出"回复序号或订单号都可以"：两种输入都会在续接时被识别，
     * 不写清楚的话用户可能以为必须原样复制订单号。
     *
     * @param options 候选项
     * @return 追问文本
     */
    private String buildOptionHint(List<ClarificationOption> options) {
        StringBuilder builder = new StringBuilder(
                "好的，为了帮您查询，请从下面的订单里选一个（直接回复序号或订单号都可以）：");
        for (int i = 0; i < options.size(); i++) {
            builder.append('\n').append(i + 1).append(") ").append(options.get(i).label());
        }
        return builder.toString();
    }

    /**
     * 把本轮追问挂起到会话上，供用户下一轮回答时续接。
     *
     * <p>写库失败<b>不影响本轮追问</b>：话术照样发得出去，
     * 只是用户回答序号时无法续接（会当作新问题重走一遍流程，
     * 而"MC202409240001 这个订单"这类回答仍可能被意图识别捞回来）。
     * 因为一次可选的增强而让整轮对话失败，是不划算的。
     *
     * @param context     对话上下文
     * @param targetParam 候选项要填的参数名
     * @param options     候选项
     */
    private void savePendingClarification(ChatContext context, String targetParam,
                                         List<ClarificationOption> options) {
        List<PendingClarification.Option> persisted = new ArrayList<>(options.size());
        for (ClarificationOption option : options) {
            persisted.add(PendingClarification.Option.builder()
                    .value(option.value())
                    .label(option.label())
                    .build());
        }

        PendingClarification pending = PendingClarification.builder()
                .question(context.getUserMessage())
                .intent(context.getIntent().name())
                .missingParam(targetParam)
                .options(persisted)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            conversationService.savePendingClarification(context.getSessionId(), pending);
        } catch (Exception e) {
            log.error("挂起澄清状态失败，本轮追问将无法续接：session={}", context.getSessionId(), e);
        }
    }

    /**
     * 找出缺失的必需参数。
     *
     * @param context 对话上下文
     * @return 缺失的参数名列表，无缺失时为空列表
     */
    private List<String> findMissingParams(ChatContext context) {
        List<String> missing = new ArrayList<>();
        for (String param : context.getIntent().getRequiredParams()) {
            Object value = context.getParams().get(param);
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(param);
            }
        }
        return missing;
    }

    /**
     * 按缺失的参数名生成追问话术。
     *
     * @param missingParams 缺失的参数名
     * @return 追问文本
     */
    private String buildClarifyMessage(List<String> missingParams) {
        List<String> labels = new ArrayList<>(missingParams.size());
        for (String param : missingParams) {
            labels.add(PARAM_LABELS.getOrDefault(param, param));
        }
        return "好的，为了帮您查询，麻烦提供一下" + String.join("和", labels) + "。";
    }
}
