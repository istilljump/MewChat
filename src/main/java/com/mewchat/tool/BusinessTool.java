package com.mewchat.tool;

import java.util.List;
import java.util.Map;

/**
 * 业务工具契约 —— 一个业务能力（订单、物流、退款政策……）对应一个实现类。
 *
 * <p><b>与 {@link ToolInvoker} 的分工</b>：{@code ToolInvoker} 是编排层看到的
 * <b>唯一入口</b>（按工具名分发）；本接口是"一个具体工具"的形状，
 * 由 {@link BusinessToolInvoker} 收集成"工具名 → 实现"的表。
 * 编排层不认识任何具体工具类，新增业务能力不需要改动编排代码。
 *
 * <p><b>为什么实现类还要写 {@code @Tool} 注解的方法</b>：
 * <ul>
 *     <li>本接口的 {@link #invoke(Map)} 服务于"确定性调用"路径 —— 意图由状态机判定、
 *         参数由 {@code IntentRecognizer} 抽取，工具名由编排侧查表得到，
 *         全程不需要大模型选择工具，也就没有工具误选的可能</li>
 *     <li>被 {@code @Tool} 标注的方法才是工具<b>对模型可见的契约</b>：
 *         名称、用途说明、参数结构都写在那里，供将来走模型自主调用
 *         （LangChain4j {@code ToolSpecifications}）时生成 JSON Schema。
 *         名称必须与 {@link #name()} 一致 —— 两者引用同一个常量，不可能写歪</li>
 * </ul>
 *
 * <p><b>实现约定</b>（编排层依赖这些约定做降级判断，务必遵守）：
 * <ul>
 *     <li>不抛异常：失败返回 {@link ToolResult#fail}，异常会让整轮对话降级为兜底</li>
 *     <li>不返回 null：编排层虽有判空兜底，但那是防御，不是接口许可</li>
 *     <li>信息不足返回 {@link ToolResult#needMoreInfo}，
 *         记录不存在返回 {@link ToolResult#notFound} —— 两者都走追问分支，
 *         而不是含糊地报"调用失败"让用户以为系统坏了</li>
 *     <li>入参出参都是结构化对象：大模型只负责从用户话里提取参数，
 *         业务数据一律由工具查出来，模型不参与生成</li>
 * </ul>
 *
 * @author MewChat
 */
public interface BusinessTool {

    /**
     * 工具名，编排层按它查表调用。
     *
     * <p>必须与实现类上 {@code @Tool(name = ...)} 的值一致，且全局唯一 ——
     * 重名会在启动时报错而不是互相覆盖（见 {@link BusinessToolInvoker} 的构造校验）。
     *
     * @return 工具名，如 {@code order_query}
     */
    String name();

    /**
     * 执行工具。
     *
     * @param params 从用户输入中抽取到的参数，键为参数名（如 orderNo）；
     *               可能缺少键，实现类需要自己判断必需参数是否齐全。
     *               <b>不会为 null</b>，但可能为空表
     * @return 调用结果，<b>不应为 null</b>
     */
    ToolResult invoke(Map<String, Object> params);

    /**
     * 必需参数缺失时，为澄清追问提供候选选项。
     *
     * <p>默认没有候选（空列表），此时追问只能干问一句"请提供订单号"。
     * 能列出候选的工具会让追问好用得多 —— 用户记不住订单号，
     * 但看到自己的订单列表就能认出来。
     *
     * <p><b>为什么用户身份由参数传入，而不是从 {@code params} 里取</b>：
     * {@code params} 是大模型从用户话里抽出来的，若身份也从那里取，
     * 等于让模型决定"查谁的订单"—— 模型完全可能被诱导着填上别人的ID。
     * 身份必须来自服务端的认证上下文。
     *
     * @param userId 当前登录用户ID，可能为空（游客/内部调用）
     * @return 候选项，无候选时返回空列表
     */
    default List<ClarificationOption> listOptions(Long userId) {
        return List.of();
    }

    /**
     * 本工具给出的候选项应当填进哪个参数名。
     *
     * <p><b>为什么必须由工具自己声明，而不是让编排层去猜</b>：候选值与参数名的对应关系
     * 只有提供候选的工具知道 —— 订单工具给的是订单号、商品工具给的是商品名。
     * 编排层若按"缺的参数里有没有 orderNo"之类的规则去推断，
     * 每接一个新工具就要改一次编排层的判断逻辑，而且推错的后果是静默的：
     * 用户选了第 1 项，那个值被填进了另一个参数，工具拿着它去查，查出"没找到"。
     *
     * <p>默认返回 {@code null}，表示本工具不提供候选项（追问退回干问一句"请提供 X"）。
     * 只要 {@link #listOptions(Long)} 会返回非空列表，本方法就必须给出参数名。
     *
     * @return 候选项要填的参数名；无候选时为 null
     */
    default String clarificationParam() {
        return null;
    }

    /**
     * 从参数表里取一个字符串参数，统一做"取值 → 转字符串 → 去空白 → 空串视为没给"。
     *
     * <p>取值的这步规范化放在契约里而不是各工具里各写一遍，是因为它踩的坑完全一样：
     * {@code params} 来自大模型输出的 JSON，订单号可能被当成数字（于是这里拿到
     * {@code Long}/{@code Integer}），也可能带首尾空格，还可能是个空串。
     * 三份一模一样的实现迟早会有一份忘了去空白，而"带空格的订单号查不到订单"
     * 这种问题极难从日志上看出来。
     *
     * @param params 参数表，允许为 null
     * @param name   参数名
     * @return 去空白后的参数值；参数不存在或为空白时返回 null
     */
    static String stringParam(Map<String, Object> params, String name) {
        Object value = params == null ? null : params.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
