package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 可观测性配置，对应 {@code application.yml} 的 {@code mewchat.observability.langfuse.*}。
 *
 * <p>上报的是<b>每轮对话的摘要</b>（token、耗时、检索命中、工具调用、置信度），
 * 不是模型调用的完整 trace。这些指标本地已经落库（{@code message} 表的冗余字段），
 * 接 Langfuse 的意义在于把它们放到时间轴上做趋势与对比，
 * 以及和提示词版本、模型版本关联起来看。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.observability.langfuse")
public class ObservabilityProperties {

    /**
     * 是否启用上报。
     *
     * <p>默认开启，但<b>没有配置密钥时不会真的发请求</b>（见 {@link #isConfigured()}）——
     * 这样才能做到"开箱即用的默认值不会产生副作用"：
     * 没接 Langfuse 的部署只会看到一行 INFO 日志，而不是一堆连接失败告警。
     */
    private boolean enabled = true;

    /** Langfuse 服务地址。自建部署填自己的域名 */
    private String host = "https://cloud.langfuse.com";

    /** 项目公钥（Langfuse 的 public key） */
    private String publicKey;

    /** 项目私钥（Langfuse 的 secret key）。属凭据，不要写进代码或提交到仓库 */
    private String secretKey;

    /**
     * 单次上报的超时时间。
     *
     * <p>必须设得短：可观测性上报是"锦上添花"，绝不能因为它慢而拖累业务。
     * 上报本身是异步的，但超时短能让失败尽早暴露在日志里。
     */
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * 是否把上报内容打进日志。
     *
     * <p>默认关闭：内容里含用户问题与回答，属于用户数据，
     * 默认打日志等于默认把用户对话写进日志文件。只在排查上报问题时临时打开。
     */
    private boolean logPayload = false;

    /**
     * 是否具备上报条件。
     *
     * @return true 表示已启用且密钥齐全
     */
    public boolean isConfigured() {
        return enabled && StringUtils.hasText(host)
                && StringUtils.hasText(publicKey) && StringUtils.hasText(secretKey);
    }
}
