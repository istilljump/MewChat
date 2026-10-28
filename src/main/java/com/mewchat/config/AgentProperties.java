package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Agent 编排配置，对应 {@code application.yml} 的 {@code mewchat.agent.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.agent")
public class AgentProperties {

    /**
     * 置信度达标线：有效置信度<b>不低于</b>本值时直接生成回答。
     *
     * <p>这是"敢不敢直接答"的界限。调高会更谨慎（更多回答被拉去做补充检索或转人工），
     * 调低则更容易把不太有把握的答案直接讲给用户。属产品取舍，需按实际效果调整。
     */
    private BigDecimal highConfidenceThreshold = new BigDecimal("0.70");

    /**
     * 置信度兜底线：有效置信度<b>低于</b>本值时直接兜底（转人工 + 建工单）。
     *
     * <p>与 {@link #highConfidenceThreshold} 之间构成中档区间：
     * 落在区间内说明"像是有依据、但不够扎实"，值得做一次放宽条件的补充检索再判，
     * 而不是直接放弃或直接硬答。
     */
    private BigDecimal lowConfidenceThreshold = new BigDecimal("0.40");

    /**
     * 兜底时是否自动创建人工工单。
     *
     * <p>默认开启：兜底意味着"系统答不上来"，没有工单就等于这个问题没人接手。
     * 可关闭的原因只有一个 —— 联调或压测时不想污染工单表。
     */
    private boolean autoCreateTicket = true;

    /**
     * 澄清挂起状态的有效期（分钟）。
     *
     * <p>用户被追问"请选择订单"后如果一直没回答，这条挂起状态不该无限期留着：
     * 半天之后他随口回一个"1"，会被当成在回答那个早已过时的追问。
     * 超期即视为失效，按新问题走正常流程。
     */
    private int clarifyPendingTtlMinutes = 30;

    /**
     * 状态机最大步数。
     *
     * <p>转移表里有两个受控的环（置信度校验 → 知识检索 → 置信度校验；
     * 多意图的聚合 → 路由 → 聚合），本参数是它们的兜底保险：
     * 环的终止条件由业务逻辑保证（只补检索一次、子意图数封顶），
     * 万一那处逻辑写错，这里会在超步数时抛异常并打出完整轨迹，
     * 而不是让请求线程无限打转。
     *
     * <p>取 32 而不是更早的 20：多意图轮每个子意图要经历
     * 路由 → 工具/检索 → 置信度 → 回复 约 4~5 步，3 个子意图加上入口与聚合
     * 已接近 20 —— 上限太紧会把合法的多意图轮误判成失控。
     */
    private int maxSteps = 32;

    /**
     * 是否启用"模型自主选择工具"（Function Calling）。
     *
     * <p>关闭（默认）时工具完全由"意图 → 查表"决定；开启后 {@code ToolSpecialist}
     * 先让模型在工具调用循环里自行决定查什么（支持一次组合多个工具，如
     * "我的订单到哪了"= 查订单 + 查物流），模型不选工具、超轮数或调用异常时
     * <b>自动降级回查表路径</b>——行为不劣于关闭时。
     *
     * <p>默认关闭的理由：工具选择的正确性从"查表（确定）"变成"模型（概率）"，
     * 应当在评测集上对比两种模式的通过率后再决定演示/生产用哪个。
     */
    private boolean modelToolCalling = false;

    /**
     * 模型自主选择工具时，工具调用循环的最大轮数。
     *
     * <p>一轮 = 模型一次响应（可能携带多个工具调用请求）。上限是失控保险：
     * 模型陷入"调工具 → 还要再调"的循环时，到轮数即放弃模型路径、降级回查表，
     * 而不是陪着模型烧 token。
     */
    private int modelToolCallingMaxRounds = 3;

    /**
     * 是否启用多意图拆解。
     *
     * <p>关闭（默认）时一句话只按一个意图处理；开启后意图识别会把一句话里的
     * 多个独立问题拆成子意图，编排层用受控环逐条执行、最后把各段回复
     * 聚合成一条（如"查订单 MC1 到哪了，再看看耳机多少钱"一轮同时回答）。
     *
     * <p>默认关闭的理由与"模型自主选工具"相同：拆解正确性从确定变概率
     * （模型可能把一句话拆错、也可能该拆不拆），且每多一条子意图就多一轮
     * 模型调用与可能的检索/工具成本，应在评测集上对比后再决定。
     * 任何拆解失败都会降级为单意图处理，最坏情形等于关闭。
     */
    private boolean multiIntent = false;

    /**
     * 多意图拆解的最大子意图数。
     *
     * <p>受控环的圈数上限：模型把一句话拆出再多"问题"，也只处理前 N 条
     * （按用户提到的顺序取前 N）。没有上限的拆解等于把一次提问放大成
     * 无限次模型调用，成本与延迟都不可控。
     */
    private int multiIntentMaxSubIntents = 3;
}
