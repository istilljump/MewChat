package com.mewchat.service;

import com.mewchat.api.admin.dto.KnowledgeDraftView;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.rag.RagService;
import com.mewchat.rag.retrieval.RetrievedChunk;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库草稿生成：为低置信度问题让模型起草候选知识文档。
 *
 * <p>这是数据飞轮"补知识"一步的智能化升级：原来运营看完待优化清单要
 * 从零手写文档，现在由模型基于<b>已有知识库的相似片段</b>起草一份候选稿，
 * 运营审核、修改后经既有的文档录入接口入库，再走"标记已优化"闭环。
 *
 * <p><b>草稿绝不直接入库</b>：模型起草的内容可能有幻觉，知识库又是
 * 客服所有回答的依据 —— 所以本服务只产出草稿、只读不写，
 * "是否采纳、怎么改"的决策权留在运营手里。入库与状态回写沿用既有接口
 * （{@code POST /api/admin/knowledge/documents} + {@code POST .../questions/{id}/optimize}），
 * 本服务不重复实现它们。
 *
 * <p><b>上下文为什么带知识库片段</b>：让模型"凭常识写政策"是编造的温床；
 * 把检索到的相似片段放进提示词，草稿至少有据可依，且与库里已有内容风格一致。
 * 检索失败不阻断起草（降级为无上下文起草，正文中标注待人工确认）。
 *
 * @author MewChat
 */
@Service
public class KnowledgeDraftService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDraftService.class);

    /**
     * 起草提示词。
     *
     * <p>输出契约刻意用"第一行标题"而不是 JSON：模型对纯文本格式的遵循
     * 远比 JSON 稳定，而解析失败的兜底（拿原问题当标题）也让坏输出不至于不可用。
     */
    private static final String SYSTEM_PROMPT = """
            你是电商客服知识库的编辑助手。任务：为客服答不上来的用户问题起草一篇知识库文档草稿。
            输出格式（严格遵守）：
            第一行：标题：<不超过 40 字的文档标题>
            之后：文档正文（Markdown，可直接回答用户问题的说明，包含规则、步骤、注意事项）。
            要求：
            1. 只能依据给出的"现有知识库相关片段"和电商客服通用常识起草；
               凡是没有片段依据、属于你补写的内容，必须在句末标注"（待人工确认）"。
            2. 不要寒暄，不要解释你在做什么，直接输出标题行和正文。""";

    /** 送给模型的参考片段上限：再多只会稀释重点、多烧 token */
    private static final int MAX_REFERENCE_CHUNKS = 3;

    /** 同簇问法带给模型的上限：问法的多样性对起草有用，但也不用全量 */
    private static final int MAX_CLUSTER_VARIANTS = 5;

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final RagService ragService;

    private final LowConfidenceQuestionService questionService;

    public KnowledgeDraftService(ObjectProvider<ChatModel> chatModelProvider,
                                 RagService ragService,
                                 LowConfidenceQuestionService questionService) {
        this.chatModelProvider = chatModelProvider;
        this.ragService = ragService;
        this.questionService = questionService;
    }

    /**
     * 为问题池里的某条问题起草知识文档。
     *
     * @param questionId 问题池记录ID
     * @return 草稿视图（含标题、正文、参考片段标题）；不落库
     * @throws BizException 问题不存在、已是已优化状态、或模型服务不可用时抛出
     */
    public KnowledgeDraftView draft(Long questionId) {
        LowConfidenceQuestion question = questionService.getById(questionId);
        if (question == null) {
            throw new BizException(ResultCode.NOT_FOUND, "问题不存在：" + questionId);
        }
        if (question.getOptimized() != null && question.getOptimized() == 1) {
            throw new BizException(ResultCode.PARAM_INVALID, "该问题已标记为已优化，无需再起草");
        }

        List<RetrievedChunk> references = retrieveReferences(question.getQuestion());
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "模型服务不可用，无法生成草稿");
        }

        String generated = chatModel.chat(List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(buildUserPrompt(question, references))))
                .aiMessage().text();

        KnowledgeDraftView view = parse(question.getQuestion(), generated, references);
        log.info("知识草稿已生成（未落库）：questionId={} 标题={} 参考片段={}",
                questionId, view.title(), references.size());
        return view;
    }

    /** 检索相似知识片段作为起草依据；失败降级为空列表（无上下文起草），不阻断 */
    private List<RetrievedChunk> retrieveReferences(String question) {
        try {
            List<RetrievedChunk> chunks = ragService.search(question).getChunks();
            return chunks == null ? List.of() : chunks.subList(0, Math.min(chunks.size(), MAX_REFERENCE_CHUNKS));
        } catch (Exception e) {
            log.warn("起草时检索知识库失败，降级为无上下文起草：{}", e.getMessage());
            return List.of();
        }
    }

    /** 组装用户提示词：问题原文 + 同簇问法 + 知识库片段 */
    private String buildUserPrompt(LowConfidenceQuestion question, List<RetrievedChunk> references) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【用户问题】").append(question.getQuestion()).append('\n');

        List<String> variants = clusterVariants(question);
        if (!variants.isEmpty()) {
            prompt.append("【同簇的其它问法】\n");
            variants.forEach(v -> prompt.append("- ").append(v).append('\n'));
        }

        if (references.isEmpty()) {
            prompt.append("【现有知识库相关片段】无 —— 请基于电商客服通用常识起草，"
                    + "没有把握的内容务必标注（待人工确认）\n");
        } else {
            prompt.append("【现有知识库相关片段】\n");
            for (RetrievedChunk chunk : references) {
                prompt.append("《").append(chunk.getDocTitle()).append("》")
                        .append(chunk.getText()).append('\n');
            }
        }
        return prompt.toString();
    }

    /** 取同簇的其它问法（含跨问法的措辞差异），失败静默返回空列表 */
    private List<String> clusterVariants(LowConfidenceQuestion question) {
        if (!StringUtils.hasText(question.getClusterKey())) {
            return List.of();
        }
        try {
            List<LowConfidenceQuestion> siblings = questionService.lambdaQuery()
                    .eq(LowConfidenceQuestion::getClusterKey, question.getClusterKey())
                    .ne(LowConfidenceQuestion::getId, question.getId())
                    .last("LIMIT " + MAX_CLUSTER_VARIANTS)
                    .list();
            List<String> variants = new ArrayList<>(siblings.size());
            for (LowConfidenceQuestion sibling : siblings) {
                if (StringUtils.hasText(sibling.getQuestion())) {
                    variants.add(sibling.getQuestion());
                }
            }
            return variants;
        } catch (Exception e) {
            log.warn("取同簇问法失败（不影响起草）：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析模型输出为草稿。
     *
     * <p>第一行按"标题：xxx"解析；格式不符合契约时兜底为
     * "拿原问题当标题、全文当正文" —— 草稿本来就是给人改的，坏格式不该让它不可用。
     */
    private KnowledgeDraftView parse(String question, String generated, List<RetrievedChunk> references) {
        String text = generated == null ? "" : generated.trim();
        String title = null;
        String content = text;
        if (text.startsWith("标题：") || text.startsWith("标题:")) {
            int newline = text.indexOf('\n');
            title = (newline < 0 ? text : text.substring(0, newline))
                    .substring("标题：".length()).trim();
            content = newline < 0 ? "" : text.substring(newline + 1).trim();
        }
        if (!StringUtils.hasText(title)) {
            title = question.length() > 40 ? question.substring(0, 40) : question;
        }

        Set<String> referenceTitles = new LinkedHashSet<>();
        for (RetrievedChunk chunk : references) {
            if (StringUtils.hasText(chunk.getDocTitle())) {
                referenceTitles.add(chunk.getDocTitle());
            }
        }
        return new KnowledgeDraftView(title, content, List.copyOf(referenceTitles));
    }
}
