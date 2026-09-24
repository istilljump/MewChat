package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全护栏配置，对应 {@code application.yml} 的 {@code mewchat.guardrail.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.guardrail")
public class GuardrailProperties {

    /**
     * 是否启用敏感词拦截。
     *
     * <p>默认开启。关掉它的唯一合理场景是排查"是不是护栏误拦了我的正常问题" ——
     * 排查完应当立刻恢复，因为关掉期间违规内容会被正常送给大模型。
     */
    private boolean enabled = true;

    /**
     * 敏感词列表。
     *
     * <p><b>从配置读取而不是写死在代码里</b>：这类词表要随运营与监管要求随时调整，
     * 为了加一个词走一次发版流程是不现实的。生产环境应当换成配置中心或词库表，
     * 但接口形状（一个字符串列表）可以不变。
     *
     * <p><b>词表本身要慎选</b>：匹配是"包含即命中"的（中文分词边界不可靠），
     * 因此不能放常见单字或常用词 —— 一个"赌"字会把"押金赌博纠纷"这类
     * 正常投诉也一并拦下。词表应放完整词组，宁可漏一点，也不要大面积误伤。
     */
    private List<String> sensitiveWords = new ArrayList<>();

    /**
     * 命中护栏时的固定回复。
     *
     * <p>话术里<b>不能出现命中的那个词</b>，也不说明"因为哪个词被拦下"——
     * 那等于告诉对方规则边界在哪，会被用来反复试探。
     */
    private String rejectReply =
            "抱歉，您的问题涉及不符合平台规范的内容，我无法回答。"
                    + "如果您有其他问题，我很乐意继续帮您。";
}
