package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话记忆配置，对应 {@code application.yml} 的 {@code mewchat.memory.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.memory")
public class MemoryProperties {

    /**
     * 短时记忆保留的对话轮数。
     *
     * <p>一轮 = 一次用户提问 + 一次助手回复，因此实际加载的消息条数约为本值的 2 倍。
     * 这是上下文长度与 token 成本的平衡点：值越大理解越准，但每轮请求的
     * token 消耗线性增长。
     */
    private int maxHistoryRounds = 10;

    /** 会话摘要的最大字数，用于约束生成摘要的长度 */
    private int summaryMaxChars = 200;

    /**
     * 是否启用指代消解。
     *
     * <p>关闭后直接使用用户原始输入，可省掉一次大模型调用（降低延迟与成本），
     * 代价是多轮对话里"它""那单"这类指代无法被正确理解。
     */
    private boolean referenceResolutionEnabled = true;

    /** 生成摘要时最多送入的对话条数，防止超长会话撑爆上下文 */
    private int summaryMaxMessages = 60;
}
