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
     * <p>转移表里有一个受控的环（置信度校验 → 知识检索 → 置信度校验），
     * 本参数是它的兜底保险：环的终止条件由业务逻辑保证（只补检索一次），
     * 万一那处逻辑写错，这里会在超步数时抛异常并打出完整轨迹，
     * 而不是让请求线程无限打转。
     */
    private int maxSteps = 20;
}
