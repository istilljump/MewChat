package com.mewchat.service;

import com.mewchat.common.util.HashUtils;
import com.mewchat.config.GuardrailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 安全护栏：识别违规输入，交由编排层直接拒答。
 *
 * <p><b>为什么要有它</b>：大模型对输入的"意图"没有原则，你给它什么它就跟什么走。
 * 电商客服会被用来试探违规交易（代刷单、违禁品、代开发票之类），
 * 一旦模型顺着答下去，那就不再是"答错一个政策"的问题，而是平台合规问题。
 * 护栏的作用是在<b>内容进入模型之前</b>拦下这一类输入。
 *
 * <p><b>匹配前先做归一化</b>：复用 {@link HashUtils#normalizeQuestion(String)}，
 * 它会去掉空白与标点并统一小写。因此"赌 博""赌-博""ＤＵ"这类
 * 加空格、加符号的规避写法与"赌博"归一化后是同一个串，被一并拦下。
 * 不做归一化的话，绕过护栏只需要在敏感词中间插一个空格 —— 这种护栏等于没有。
 *
 * <p><b>已知局限（务必知悉）</b>：
 * <ul>
 *     <li>这是<b>基于词表的黑名单</b>，只挡得住"照着词表写的"输入。
 *         同义改写、拆字、拼音、外语表达都挡不住，
 *         它是一道"提高成本的护栏"，不是"不可绕过的防线"</li>
 *     <li>中文没有可靠的分词边界，只能按"包含即命中"判定，
 *         因此词表要放完整词组，否则会大面积误伤正常咨询</li>
 *     <li>真正的兜底应当是"模型侧的内容安全策略 + 人工复核"，词表只是最外层的快速拦截</li>
 * </ul>
 *
 * @author MewChat
 */
@Service
public class GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailService.class);

    private final GuardrailProperties properties;

    /** 归一化后的敏感词，启动时构建一次（每轮对话都要匹配，不能每次重新处理词表） */
    private final Set<String> normalizedWords;

    public GuardrailService(GuardrailProperties properties) {
        this.properties = properties;
        this.normalizedWords = normalize(properties.getSensitiveWords());

        if (properties.isEnabled() && normalizedWords.isEmpty()) {
            // 显式告警而不是静默放行：配置漏了却以为护栏在生效，是最危险的状态
            log.warn("安全护栏已启用，但 mewchat.guardrail.sensitive-words 为空，"
                    + "当前不会拦截任何内容");
        }
    }

    /**
     * 判断文本是否命中敏感词。
     *
     * @param text 用户输入
     * @return 命中的敏感词；未命中或护栏未启用时为空
     */
    public Optional<String> match(String text) {
        if (!properties.isEnabled() || !StringUtils.hasText(text) || normalizedWords.isEmpty()) {
            return Optional.empty();
        }

        String normalized = HashUtils.normalizeQuestion(text);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (String word : normalizedWords) {
            if (normalized.contains(word)) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }

    /**
     * 命中护栏时的固定回复。
     *
     * @return 拒答话术
     */
    public String rejectReply() {
        return properties.getRejectReply();
    }

    /**
     * 归一化敏感词表。
     *
     * <p>去重用的是 {@link LinkedHashSet}：保持配置里的书写顺序，
     * 使"命中的是哪个词"在多词同时命中时是确定的、可复现的。
     *
     * @param words 配置中的词表
     * @return 归一化后的词表
     */
    private static Set<String> normalize(java.util.List<String> words) {
        Set<String> normalized = new LinkedHashSet<>();
        if (CollectionUtils.isEmpty(words)) {
            return normalized;
        }
        for (String word : words) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            String candidate = HashUtils.normalizeQuestion(word);
            if (!candidate.isEmpty()) {
                normalized.add(candidate);
            }
        }
        return normalized;
    }
}
