package com.mewchat.tool;

import java.util.List;
import java.util.Map;

/**
 * 业务工具调用入口。
 *
 * <p>编排层的专家节点通过本接口调用订单、商品、物流等确定性查询能力。
 *
 * <p><b>为什么参数是 toolName 字符串而不是 agent 层的 IntentType</b>：
 * 若签名里出现 {@code IntentType}，tool 层就会反向依赖 agent 层，破坏单向分层。
 * 由专家节点负责"意图 → 工具名"的映射，工具层只认工具名，对意图一无所知。
 *
 * <p>实现由 {@link BusinessToolInvoker} 提供（按工具名分发到各业务工具）；
 * 编排层对它采用 {@code ObjectProvider} 软依赖，工具层未装配时流程降级为兜底而非启动失败。
 *
 * @author MewChat
 */
public interface ToolInvoker {

    /**
     * 调用指定工具。
     *
     * @param toolName 工具名，如 order_query、logistics_query
     * @param params   工具入参
     * @return 调用结果；<b>不要返回 null</b>，失败请用 {@link ToolResult#fail}
     */
    ToolResult invoke(String toolName, Map<String, Object> params);

    /**
     * 取某工具的候选选项，供澄清追问列一个"让用户挑"的清单。
     *
     * <p>默认没有候选：并非所有工具都谈得上"列候选"（退款政策就没有可选清单），
     * 此时追问退回干问一句"请提供 X"。
     *
     * <p>之所以放在<b>接口</b>上，而不是让编排层去强转某个具体实现类：
     * 编排层只该认识这个接口。一旦它开始 {@code instanceof} 具体类，
     * 换一个注册表实现（例如按租户隔离）就会让它编译不过，
     * 而那种改动本不该波及编排层。
     *
     * @param toolName 工具名
     * @param userId   当前登录用户ID，来自服务端认证上下文，<b>不来自模型抽取的参数</b>
     * @return 候选项列表；无候选时返回空列表，不应返回 null
     */
    default List<ClarificationOption> listOptions(String toolName, Long userId) {
        return List.of();
    }

    /**
     * 取某工具的候选项要填进哪个参数。
     *
     * <p>与 {@link #listOptions} 配套使用：后者给"让用户挑什么"，本方法给
     * "挑中的值填到哪"。两者都由工具自己声明，编排层不再推断参数名 ——
     * 推断的后果是静默的（值被填进另一个参数，工具拿着它去查，查出"没找到"）。
     *
     * @param toolName 工具名
     * @return 参数名；该工具不提供候选项时返回 null
     */
    default String clarificationParam(String toolName) {
        return null;
    }
}
