package com.mewchat.agent.memory;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.HistoryTurn;
import com.mewchat.common.constant.ChatConstants;
import com.mewchat.config.MemoryProperties;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.service.ConversationService;
import com.mewchat.service.MessageService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 会话记忆服务。
 *
 * <p>负责让 Agent"记得住"上下文，分两层：
 * <ul>
 *     <li><b>短时记忆</b> —— 滑动窗口内的最近若干轮原始对话，直接参与提示词构造</li>
 *     <li><b>长时记忆</b> —— 会话摘要，把已经说不清细节的早期内容压缩成一段话</li>
 * </ul>
 *
 * <p><b>为什么记忆不放在内存里</b>：LangChain4j 提供了
 * {@code MessageWindowChatMemory} 这类基于堆内存的窗口记忆，实现更简单，
 * 但它有三个问题：服务重启即丢失、多实例部署时各存各的、会话一多就吃光内存。
 * 本项目改为每轮从 MySQL 读最近 N 条 —— 服务完全无状态，
 * 重启和多实例都不影响上下文，代价是多一次很轻的索引查询。
 *
 * <p>分层上，本类不直接操作 Mapper，一律通过 {@code service} 层读写数据库。
 *
 * @author MewChat
 */
@Service
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);

    /**
     * 失败原因入库前的截断长度，与 {@code message.error_msg} 的列宽一致
     * （见 {@code sql/01_schema.sql}）。
     *
     * <p>必须截断：流程异常时这里放的是原始异常消息，而 JDBC / MyBatis / 模型客户端的
     * 异常消息常常把 SQL 语句与参数一起带上，动辄上千字符。MySQL 严格模式下超长会直接
     * 报 1406，于是<b>这条助手回复整行都插不进去</b> —— 用户在历史里看不到回答，
     * 消息计数也对不上，而日志只留一句"助手回复落库失败"。
     * 宁可丢掉错误详情的一部分，也不能丢掉整条回复。
     */
    private static final int ERROR_MSG_MAX_CHARS = 500;

    /** 指代消解提示词：强调"无法确定就原样返回"，避免模型自作主张编造实体 */
    private static final String REFERENCE_SYSTEM_PROMPT = """
            你是客服对话理解助手。任务：把用户最新一句话里的指代词替换成上文中的具体对象。
            指代词包括：它、他、她、这个、那个、那单、这笔、上面那个 等。

            严格遵守以下规则：
            1. 只输出改写后的那一句话本身，不要解释、不要加引号、不要加任何前后缀。
            2. 句中若没有指代词，原样输出原句。
            3. 若无法从上下文确定指代对象，原样输出原句，不要猜测。
            4. 不要添加原句中没有的信息。
            """;

    /** 摘要提示词：明确要覆盖的三个要素，避免摘要变成流水账 */
    private static final String SUMMARY_SYSTEM_PROMPT_TEMPLATE = """
            你是客服会话摘要助手。请概括给定客服对话，用于后续会话的长期记忆。

            要求：
            1. 不超过 %d 字。
            2. 覆盖三件事：用户诉求是什么、已给出什么结论或方案、还有什么未解决（没有则不提）。
            3. 只输出摘要正文，不要标题、不要"摘要："之类的字样、不要分点符号。
            """;

    private final ConversationService conversationService;

    private final MessageService messageService;

    /** 用于指代消解与会话摘要两处"理解型"调用，只用同步模型，不用流式 */
    private final ChatModel chatModel;

    private final MemoryProperties memoryProperties;

    public ChatMemoryService(ConversationService conversationService,
                             MessageService messageService,
                             ChatModel chatModel,
                             MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatModel = chatModel;
        this.memoryProperties = memoryProperties;
    }

    /* ==================== 加载上下文 ==================== */

    /**
     * 确保会话记录存在。
     *
     * @param sessionId 会话业务ID
     * @param userId    用户ID，游客传 null
     * @return 已存在或新建的会话
     */
    public Conversation ensureConversation(String sessionId, Long userId) {
        return conversationService.getOrCreate(sessionId, userId);
    }

    /**
     * 加载短时记忆：滑动窗口内的最近若干轮对话。
     *
     * <p>只保留 user / assistant 两种角色：system 提示词每轮现拼、
     * tool 结果只在当轮有效，都不该进入历史。
     *
     * @param sessionId 会话业务ID
     * @return 时间正序的历史发言，不含本轮用户输入
     */
    public List<HistoryTurn> loadRecentHistory(String sessionId) {
        int limit = Math.max(1, memoryProperties.getMaxHistoryRounds()) * 2;
        List<Message> messages = messageService.listRecentBySessionId(sessionId, limit);

        List<HistoryTurn> history = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (isHistoryRole(message.getRole())) {
                history.add(new HistoryTurn(message.getRole(), message.getContent()));
            }
        }
        log.debug("加载短时记忆：session={} 消息 {} 条，其中有效历史 {} 轮",
                sessionId, messages.size(), history.size());
        return history;
    }

    /**
     * 加载长时记忆：会话摘要。
     *
     * @param sessionId 会话业务ID
     * @return 摘要，没有则返回 null
     */
    public String loadSummary(String sessionId) {
        Conversation conversation = conversationService.getBySessionId(sessionId);
        return conversation == null ? null : conversation.getSummary();
    }

    /* ==================== 指代消解 ==================== */

    /**
     * 指代消解：把用户输入里的"它""那单"等补全为具体实体。
     *
     * <p>为什么必须做：多轮对话中用户常说"那这个能退吗"，
     * 直接拿这句话去检索必然召回不到东西。补全成"七天无理由退货政策能退吗"
     * 之后检索才有意义。
     *
     * <p>降级策略（每一步都保证不返回空）：
     * 无历史 → 直接返回原句（没有上下文可消解，省一次模型调用）；
     * 功能被配置关闭 → 返回原句；模型调用失败或输出异常 → 返回原句。
     * 消解只是增强，绝不能因为它失败而让整轮对话挂掉。
     *
     * @param sessionId   会话业务ID
     * @param userMessage 用户原始输入
     * @return 消解后的输入，无法消解时即原输入
     */
    public String resolveReferences(String sessionId, String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return userMessage;
        }
        if (!memoryProperties.isReferenceResolutionEnabled()) {
            return userMessage;
        }

        List<HistoryTurn> history = loadRecentHistory(sessionId);
        if (CollectionUtils.isEmpty(history)) {
            log.debug("无历史对话，跳过指代消解：session={}", sessionId);
            return userMessage;
        }

        String userPrompt = "【最近对话】\n" + buildHistoryText(history)
                + "\n【最新一句】\n" + userMessage;
        String rewritten = callModel(REFERENCE_SYSTEM_PROMPT, userPrompt);

        if (!StringUtils.hasText(rewritten)) {
            return userMessage;
        }
        rewritten = stripQuotes(rewritten.trim());
        if (!isPlausibleRewrite(rewritten, userMessage)) {
            log.warn("指代消解输出异常，回退为原句：session={} 输出长度={}", sessionId, rewritten.length());
            return userMessage;
        }

        if (!rewritten.equals(userMessage)) {
            log.debug("指代消解：'{}' -> '{}'", userMessage, rewritten);
        }
        return rewritten;
    }

    /* ==================== 更新记忆 ==================== */

    /**
     * 保存用户消息。
     *
     * <p>在加载历史<b>之后</b>调用，否则本轮输入会被算进历史、
     * 在提示词里重复出现一次。
     *
     * @param sessionId 会话业务ID
     * @param content   用户输入原文（存原文而非消解结果，保留真实语料）
     */
    public void saveUserMessage(String sessionId, String content) {
        Message message = Message.builder()
                .sessionId(sessionId)
                .role(ChatConstants.ROLE_USER)
                .content(content)
                .status(1)
                .build();
        messageService.save(message);
        // 首条用户消息兼作会话标题（列注释里的既定设计）：会话列表要显示"这段对话是关于什么"，
        // 没有标题就只能显示一串会话ID。只在标题为空时写入，因此第二条消息起不再改动
        conversationService.updateTitleIfBlank(sessionId, content);
        conversationService.touchOnNewMessage(sessionId, LocalDateTime.now());
    }

    /**
     * 保存助手回复，把本轮统计一并落库。
     *
     * <p>从 {@link ChatContext} 里取数据而不是逐个参数传：一次回复要记的东西
     * 有十来个字段（回复文本、意图、置信度、token、耗时、引用来源），
     * 参数列表会长到无法阅读，而上下文对象本就承载着这些信息。
     *
     * @param context 已执行完毕的对话上下文
     * @return 落库后的消息ID；没有可保存内容时返回 null
     */
    public Long saveAssistantReply(ChatContext context) {
        if (!StringUtils.hasText(context.getReplyText())) {
            return null;
        }

        Message message = Message.builder()
                .sessionId(context.getSessionId())
                .role(ChatConstants.ROLE_ASSISTANT)
                .content(context.getReplyText())
                .costMs(context.getCostMs() == null ? null : context.getCostMs().intValue())
                .promptTokens(nullToZero(context.getPromptTokens()))
                .completionTokens(nullToZero(context.getCompletionTokens()))
                .totalTokens(nullToZero(context.getTotalTokens()))
                .modelName(context.getModelName())
                .agentName(context.getHandlerAgent())
                .confidence(context.effectiveConfidence())
                .refDocs(toRefDocs(context.getRetrievedChunks()))
                .status(context.getErrorMessage() == null ? 1 : 0)
                .errorMsg(truncateError(context.getErrorMessage()))
                .build();
        messageService.save(message);
        conversationService.touchOnNewMessage(context.getSessionId(), LocalDateTime.now());
        // 把主键回填给上下文：done 事件要带上它，前端点赞才有的放矢
        context.setAssistantMessageId(message.getId());
        return message.getId();
    }

    /* ==================== 生成摘要 ==================== */

    /**
     * 生成会话摘要并写入会话表（长时记忆）。
     *
     * <p>把已有的摘要一并喂给模型，让它做"增量更新"而不是从头重写 ——
     * 超长会话只会送入最近若干条消息，没有旧摘要的话早期信息就永久丢失了。
     *
     * @param sessionId 会话业务ID
     * @return 生成的摘要；无可总结内容或模型失败时返回 null
     */
    public String generateSummary(String sessionId) {
        List<Message> all = messageService.listAllBySessionId(sessionId);
        if (CollectionUtils.isEmpty(all)) {
            log.debug("会话 {} 没有消息，跳过摘要生成", sessionId);
            return null;
        }

        int maxMessages = Math.max(2, memoryProperties.getSummaryMaxMessages());
        List<Message> selected = all.size() <= maxMessages
                ? all
                : all.subList(all.size() - maxMessages, all.size());

        StringBuilder userPrompt = new StringBuilder();
        String previousSummary = loadSummary(sessionId);
        if (StringUtils.hasText(previousSummary)) {
            userPrompt.append("【此前已生成的摘要】\n").append(previousSummary).append("\n\n");
        }
        userPrompt.append("【本次新增对话】\n");
        for (Message message : selected) {
            if (!isHistoryRole(message.getRole())) {
                continue;
            }
            userPrompt.append(roleLabel(message.getRole())).append('：')
                    .append(message.getContent()).append('\n');
        }

        String systemPrompt = String.format(SUMMARY_SYSTEM_PROMPT_TEMPLATE,
                Math.max(50, memoryProperties.getSummaryMaxChars()));
        String summary = callModel(systemPrompt, userPrompt.toString());
        if (!StringUtils.hasText(summary)) {
            log.warn("会话 {} 摘要生成失败，保留原摘要", sessionId);
            return null;
        }

        summary = stripQuotes(summary.trim());
        conversationService.updateSummary(sessionId, summary);
        log.info("会话 {} 摘要已更新，长度 {}", sessionId, summary.length());
        return summary;
    }

    /**
     * 结束会话：生成摘要并置为已结束。
     *
     * <p><b>注意</b>：当前没有任何地方自动调用本方法。会话"结束"需要一个触发源，
     * 可选方案：用户在界面上点结束（对话接口提供 endSession 接口）、
     * 或由 {@code job} 包的定时任务扫描长时间无新消息的会话。
     * 在触发源落地前，长时记忆不会真正产生数据。
     *
     * @param sessionId 会话业务ID
     */
    public void closeSession(String sessionId) {
        generateSummary(sessionId);
        conversationService.closeSession(sessionId);
        log.info("会话 {} 已结束", sessionId);
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 调用同步对话模型。
     *
     * <p>统一捕获异常并返回 null，把"模型不可用"的决策权交给调用方：
     * 记忆相关的能力都是增强项，失败应该降级而不是中断对话。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型输出文本，失败返回 null
     */
    private String callModel(String systemPrompt, String userPrompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            return chatModel.chat(messages).aiMessage().text();
        } catch (Exception e) {
            log.warn("调用大模型失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断模型输出是否是一个合理的改写结果。
     *
     * <p>防御两类常见跑偏：模型返回空/只有标点；模型不守指令、
     * 输出一大段解释而不是一句话。超出原句长度三倍且多出 60 字以上即认为跑偏。
     *
     * @param rewritten 模型输出
     * @param original  原始输入
     * @return true 表示可以采信
     */
    private boolean isPlausibleRewrite(String rewritten, String original) {
        if (!StringUtils.hasText(rewritten)) {
            return false;
        }
        return rewritten.length() <= original.length() * 3 + 60;
    }

    /**
     * 去掉模型可能加上的成对引号。
     *
     * @param text 模型输出
     * @return 去掉包裹引号后的文本
     */
    private String stripQuotes(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            boolean paired = (first == '"' && last == '"')
                    || (first == '“' && last == '”')
                    || (first == '\'' && last == '\'');
            if (paired) {
                return text.substring(1, text.length() - 1).trim();
            }
        }
        return text;
    }

    /**
     * 把历史轮次拼成提示词文本。
     *
     * @param history 历史发言
     * @return 形如 {@code 用户：...\n客服：...} 的文本
     */
    private String buildHistoryText(List<HistoryTurn> history) {
        StringBuilder builder = new StringBuilder();
        for (HistoryTurn turn : history) {
            builder.append(roleLabel(turn.getRole())).append('：')
                    .append(turn.getContent()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 角色名转中文标签，供提示词使用。
     *
     * @param role 角色
     * @return 中文标签
     */
    private String roleLabel(String role) {
        return ChatConstants.ROLE_USER.equalsIgnoreCase(role) ? "用户" : "客服";
    }

    /**
     * 判断该角色是否应进入对话历史。
     *
     * @param role 角色
     * @return true 表示 user 或 assistant
     */
    private boolean isHistoryRole(String role) {
        return ChatConstants.ROLE_USER.equalsIgnoreCase(role)
                || ChatConstants.ROLE_ASSISTANT.equalsIgnoreCase(role);
    }

    /**
     * 把检索到的知识片段转成落库用的引用对象。
     *
     * <p>转换放在这里而不是让 rag 层直接产出 {@code MessageRefDoc}：
     * 那样 rag 层就得依赖数据访问层的实体，而引用对象的形状是"存库"的需求，
     * 属于本层职责。
     *
     * @param chunks 检索结果
     * @return 引用对象列表，无结果时返回 null（让数据库列保持 NULL 而非空数组）
     */
    private List<MessageRefDoc> toRefDocs(List<RetrievedChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return null;
        }
        List<MessageRefDoc> refDocs = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            refDocs.add(MessageRefDoc.builder()
                    .chunkId(chunk.getChunkId())
                    .docId(chunk.getDocId())
                    .docTitle(chunk.getDocTitle())
                    .chunkNo(chunk.getChunkNo())
                    .score(chunk.getScore())
                    .used(1)
                    .build());
        }
        return refDocs;
    }

    /**
     * null 安全的整数转换。
     *
     * @param value 可能为 null 的值
     * @return 原值或 0
     */
    private int nullToZero(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }

    /**
     * 把失败原因截断到列宽以内，避免整条助手回复因为一句过长的错误信息插不进去。
     *
     * @param errorMessage 原始错误信息，可为 null
     * @return 截断后的错误信息；入参为空时返回 null
     */
    private String truncateError(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return null;
        }
        if (errorMessage.length() <= ERROR_MSG_MAX_CHARS) {
            return errorMessage;
        }
        log.warn("错误信息过长已截断：原长 {} 截断为 {}", errorMessage.length(), ERROR_MSG_MAX_CHARS);
        return errorMessage.substring(0, ERROR_MSG_MAX_CHARS);
    }
}
