package com.mewchat.tool;

import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 工具调用结果。
 *
 * <p>刻意把"缺少参数"做成一种<b>正常结果</b>而不是异常：
 * 用户说"查下我的订单"却没给订单号是极常见的情况，
 * 这应该走追问分支，而不是当成错误。
 *
 * @author MewChat
 */
@Getter
@ToString
public class ToolResult {

    /** 是否调用成功 */
    private final boolean success;

    /** 工具名 */
    private final String toolName;

    /**
     * 给大模型看的自然语言摘要。
     *
     * <p>工具返回的原始结构（JSON、DTO）直接喂给模型效果不稳定，
     * 由工具自己整理成一段可读文本更可靠。
     */
    private final String summary;

    /** 结构化数据，供接口层直接返回给前端渲染（如订单卡片） */
    private final Map<String, Object> data;

    /** 失败原因，仅 success=false 时有值 */
    private final String errorMessage;

    /**
     * 缺失的必需参数名。
     *
     * <p>非空表示"信息不足，需要向用户追问"，此时 success 为 false，
     * 但它是预期内的分支而非故障。
     */
    private final List<String> missingParams;

    /**
     * 工具自己拟的追问话术。
     *
     * <p>与 {@link #missingParams} 的区别在于成因：那里是"没给参数"，
     * 这里是"参数给全了、但按它查不到记录"（见 {@link #notFound}）。
     * 两种情况都要反问用户，但该说的话不一样 ——
     * "麻烦提供订单号"和"没查到订单号 X，麻烦核对"不是一回事。
     * 只有工具自己清楚查的是什么、拿哪个值查不到，所以话术由工具写。
     *
     * <p>因此本字段<b>不</b>与 {@link #missingParams} 混用：
     * "参数没给"和"参数给了但不对"是两种成因，合并成一个字段后
     * 下游就无法区分该说"请提供"还是"请核对"了。
     */
    private final String clarifyHint;

    private ToolResult(boolean success, String toolName, String summary,
                       Map<String, Object> data, String errorMessage,
                       List<String> missingParams, String clarifyHint) {
        this.success = success;
        this.toolName = toolName;
        this.summary = summary;
        this.data = data == null ? Collections.emptyMap() : data;
        this.errorMessage = errorMessage;
        this.missingParams = missingParams == null ? Collections.emptyList() : missingParams;
        this.clarifyHint = clarifyHint;
    }

    /**
     * 构造成功结果。
     *
     * @param toolName 工具名
     * @param summary  给模型看的文本摘要
     * @param data     结构化数据
     * @return 成功结果
     */
    public static ToolResult ok(String toolName, String summary, Map<String, Object> data) {
        return new ToolResult(true, toolName, summary, data, null, List.of(), null);
    }

    /**
     * 构造失败结果。
     *
     * @param toolName 工具名
     * @param errorMessage 失败原因
     * @return 失败结果
     */
    public static ToolResult fail(String toolName, String errorMessage) {
        return new ToolResult(false, toolName, null, null, errorMessage, List.of(), null);
    }

    /**
     * 构造"信息不足需追问"的结果。
     *
     * @param toolName     工具名
     * @param missingParams 缺失的参数名
     * @return 需追问的结果
     */
    public static ToolResult needMoreInfo(String toolName, List<String> missingParams) {
        String readable = String.join("、", missingParams);
        return new ToolResult(false, toolName, null, null,
                "缺少必要信息：" + readable + "，需要向用户追问", missingParams, null);
    }

    /**
     * 构造"记录不存在、需用户核对后重新提供"的结果。
     *
     * <p><b>为什么这不算调用失败</b>：单号写错、记错、甚至把运单号当成订单号发给客服，
     * 都是对话里最常见的输入问题，能靠一句反问解决。若按失败处理（success=false、
     * 无追问话术），编排层会把置信度判为 0 并转兜底 —— 用户听到的将是
     * "系统有点忙，建议转人工"，把"您的单号可能不对"说成了"我答不上来"。
     * 因此这里与 {@link #needMoreInfo} 一样走追问分支，只是话术由工具给出。
     *
     * <p>不填 {@code missingParams}：用户确实给了参数，只是值不对，
     * 说它"缺失"会让下游生成"请提供订单号"这种答非所问的话术。
     * 该说的那句话在 {@code clarifyHint} 里。
     *
     * @param toolName    工具名
     * @param clarifyHint 工具拟的追问话术
     * @return 需追问的结果
     */
    public static ToolResult notFound(String toolName, String clarifyHint) {
        return new ToolResult(false, toolName, null, null,
                "按给定条件未查到记录", List.of(), clarifyHint);
    }

    /**
     * 是否需要向用户追问。
     *
     * @return true 表示应走追问分支
     */
    public boolean needsClarification() {
        return !missingParams.isEmpty() || clarifyHint != null;
    }
}
