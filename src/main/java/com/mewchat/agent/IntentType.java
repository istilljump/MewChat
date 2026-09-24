package com.mewchat.agent;

import java.util.List;
import java.util.Locale;

/**
 * 用户意图类型。
 *
 * <p>每个意图自带<b>路由目标</b>与<b>必需参数</b>，路由决策因此是查表而非判断，
 * 新增意图只需在枚举里加一行、无需改动调度逻辑。
 *
 * <p>意图由大模型识别，但识别结果会被严格校验：模型返回了枚举里不存在的值、
 * 或返回格式不合法，一律降级为 {@link #UNKNOWN}，从而走追问分支，
 * 而不是把异常抛到用户面前。
 *
 * @author MewChat
 */
public enum IntentType {

    /** 知识问答：售前售后政策、使用说明等，走 RAG */
    KNOWLEDGE_QA("知识问答", ChatState.RAG_RETRIEVE, List.of()),

    /** 订单查询：需要订单号等定位信息，走工具 */
    ORDER_QUERY("订单查询", ChatState.TOOL_CALL, List.of("orderNo")),

    /**
     * 物流查询：需要运单号或订单号，走工具。
     *
     * <p>这里<b>故意不声明必需参数</b>：本列表是"这些参数全都必需"的语义，
     * 而物流查询是"运单号或订单号二者取一"—— 写 {@code orderNo} 会把
     * "只给了运单号"这种完全正常的问法挡在门外（用户手上往往只有订单号，
     * 而面单上的运单号用户常常看不到，反之亦然）。这种 OR 约束由工具自己校验。
     */
    LOGISTICS_QUERY("物流查询", ChatState.TOOL_CALL, List.of()),

    /**
     * 商品查询：问价格、库存、规格等结构化事实，走工具。
     *
     * <p>与 {@link #KNOWLEDGE_QA} 的分界是"答案是不是一个确定的值"：
     * "这个耳机支持什么协议"可以由商品描述回答（知识检索），
     * "这个耳机多少钱、还有货吗"必须查到确切的值 —— 让模型从知识片段里
     * "读出"价格，是编造数字最常见的入口。
     *
     * <p>必需参数是商品名：不说清是哪件商品，价格与库存都无从谈起。
     */
    PRODUCT_QUERY("商品查询", ChatState.TOOL_CALL, List.of("productName")),

    /** 退款咨询：退款规则本身属知识类问题，走 RAG */
    REFUND_ASK("退款咨询", ChatState.RAG_RETRIEVE, List.of()),

    /** 投诉建议：先查投诉处理政策，答不好会经置信度校验降级为兜底（建议转人工） */
    COMPLAINT("投诉建议", ChatState.RAG_RETRIEVE, List.of()),

    /** 无法识别：走追问，让用户补充信息 */
    UNKNOWN("无法识别", ChatState.CLARIFY, List.of());

    /** 中文说明，同时用于构造意图识别提示词 */
    private final String label;

    /** 路由目标状态 */
    private final ChatState routeTarget;

    /**
     * 完成本意图所必需的参数名。
     *
     * <p>由处理节点在调用工具前校验，缺失时设置追问提示而不是硬调工具，
     * 避免拿空参数去查库、返回一个"查不到"的假答案。
     *
     * <p><b>语义是"全部必需"</b>，因此表达不了"二者取一"这类约束
     * （见 {@link #LOGISTICS_QUERY}）—— 那部分交给工具按自身的最小信息需求校验，
     * 工具发现缺信息时同样会把流程导向追问分支。
     */
    private final List<String> requiredParams;

    IntentType(String label, ChatState routeTarget, List<String> requiredParams) {
        this.label = label;
        this.routeTarget = routeTarget;
        this.requiredParams = requiredParams;
    }

    /**
     * 按名称解析意图，不区分大小写。
     *
     * <p>大模型输出的意图名可能带空格、大小写不一致，这里统一处理后解析；
     * 解析不到一律返回 {@link #UNKNOWN}，<b>不抛异常</b>——
     * 模型输出不可控，流程必须能兜住任何一种输出。
     *
     * @param name 模型返回的意图名，可为空
     * @return 解析到的意图，无法解析时返回 UNKNOWN
     */
    public static IntentType parse(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (IntentType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * 生成给大模型看的候选意图清单，保证提示词与枚举永远同步。
     *
     * @return 形如 {@code KNOWLEDGE_QA(知识问答), ORDER_QUERY(订单查询), ...} 的字符串
     */
    public static String candidates() {
        StringBuilder builder = new StringBuilder();
        for (IntentType type : values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(type.name()).append('(').append(type.label).append(')');
        }
        return builder.toString();
    }

    public String getLabel() {
        return label;
    }

    public ChatState getRouteTarget() {
        return routeTarget;
    }

    public List<String> getRequiredParams() {
        return requiredParams;
    }
}
