package com.mewchat.api.chat;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.supervisor.ChatSupervisor;
import com.mewchat.api.chat.dto.ChatMessageView;
import com.mewchat.api.chat.dto.ChatSendRequest;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.config.SseProperties;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.service.ConversationService;
import com.mewchat.service.MessageService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 对话接口。
 *
 * <p>三个接口的分工：
 * <ul>
 *     <li>{@code POST /api/chat/session} —— 新建会话，拿到 sessionId</li>
 *     <li>{@code POST /api/chat/send} —— 发起一轮对话，<b>SSE 流式返回</b></li>
 *     <li>{@code GET /api/chat/session/{sessionId}/history} —— 拉取会话历史，
 *         用于前端刷新后恢复对话</li>
 * </ul>
 *
 * <p><b>全部接口都要求携带 {@code Authorization: Bearer <token>}</b>
 * （见 {@code config.SecurityConfig}），并且每个会话操作都会校验归属 ——
 * 会话ID泄露一次就等于对话内容泄露，光靠"ID 猜不到"是不够的。
 *
 * <p><b>本类不写业务逻辑</b>：对话处理在 {@link ChatSupervisor}，
 * 会话与消息的读写分别在 {@link ConversationService} / {@link MessageService}。
 * 这里只做参数校验、把实体映射成对外 DTO、以及把结果包装成 {@link Result}。
 *
 * <p><b>关于"由接口层做实体到 DTO 的映射"</b>：{@code §四.8} 要求实体与对外
 * 传输对象不混用，而映射只能有一处落地 —— {@code service} 层返回实体、
 * 不应认识"对外的形状"，因此映射放在这里。这与"接口层不写业务逻辑"不冲突：
 * 逐个字段搬运是展示层的职责，不含任何判断。
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatSupervisor chatSupervisor;

    private final ConversationService conversationService;

    private final MessageService messageService;

    private final SseProperties sseProperties;

    /** 流式任务专用线程池，见 {@code config.SseConfig} */
    private final Executor sseExecutor;

    public ChatController(ChatSupervisor chatSupervisor,
                          ConversationService conversationService,
                          MessageService messageService,
                          SseProperties sseProperties,
                          @Qualifier("sseTaskExecutor") Executor sseExecutor) {
        this.chatSupervisor = chatSupervisor;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.sseProperties = sseProperties;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 新建会话。
     *
     * <p>会话ID由服务端生成，前端拿到后应在后续的 {@code /send} 里一直用它 ——
     * 换掉它等于开了新的一段对话，历史与记忆都接不上。
     *
     * @param user 当前登录用户
     * @return 新的会话ID
     */
    @PostMapping("/session")
    public Result<String> createSession(@AuthenticationPrincipal AuthenticatedUser user) {
        Conversation conversation = conversationService.createForUser(requireUserId(user));
        log.info("新建会话：sessionId={} userId={}", conversation.getSessionId(), conversation.getUserId());
        return Result.success(conversation.getSessionId());
    }

    /**
     * 查询会话历史。
     *
     * @param sessionId 会话业务ID
     * @param user      当前登录用户
     * @return 消息列表，时间正序
     * @throws BizException 会话不存在或不属于当前用户时抛出
     */
    @GetMapping("/session/{sessionId}/history")
    public Result<List<ChatMessageView>> history(@PathVariable String sessionId,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        // 归属校验必须先于读取：会话ID可以被猜到或被泄露，
        // 校验通过才说明"这个ID确实是你的"
        Conversation conversation =
                conversationService.getOwnedBySessionId(sessionId, requireUserId(user));
        if (conversation == null) {
            throw new BizException(ResultCode.FORBIDDEN, ConversationService.SESSION_UNAVAILABLE_MESSAGE);
        }

        List<ChatMessageView> views = messageService.listAllBySessionId(sessionId).stream()
                .map(this::toView)
                .toList();
        return Result.success(views);
    }

    /**
     * 发起一轮对话，以 SSE 流式返回。
     *
     * <p>事件格式见 {@link SseStreamListener}。要点有三个：
     * <ul>
     *     <li><b>归属校验在建立连接之前完成</b>：拿别人的 sessionId 发消息应当直接收到
     *         一个 JSON 错误，而不是先收到一串 SSE 状态事件再收到错误 ——
     *         后者会让前端以为对话已经开始</li>
     *     <li><b>处理在独立线程池执行</b>：一次回答可能耗时数十秒（含检索与工具调用），
     *         占着 Tomcat 的工作线程会让少量并发就拖垮整个服务（见 {@code config.SseConfig}）</li>
     *     <li><b>线程池满时立即回一个错误事件</b>：队列积压时让请求无限等待，
     *         只会把所有用户一起拖慢；直接告知"稍后重试"更诚实</li>
     * </ul>
     *
     * @param request 对话请求
     * @param user    当前登录用户
     * @return SSE 事件流
     * @throws BizException 会话不属于当前用户时抛出
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@Valid @RequestBody ChatSendRequest request,
                           @AuthenticationPrincipal AuthenticatedUser user) {
        long userId = requireUserId(user);
        conversationService.resolveOwnedSession(request.sessionId(), userId);

        // 超时取配置值：不设置在长回答（多轮工具调用）场景下会被容器默认值中途掐断
        SseEmitter emitter = new SseEmitter(sseProperties.getTimeoutMs());
        SseStreamListener listener = new SseStreamListener(emitter);

        try {
            sseExecutor.execute(() -> runChat(request, userId, listener));
        } catch (RejectedExecutionException e) {
            log.warn("流式任务被拒绝（线程池已满）：session={}", request.sessionId());
            listener.fail(ResultCode.SYSTEM_ERROR.getCode(), "当前咨询人数较多，请稍后重试");
        }

        // 必须立刻返回 emitter：任务在别的线程里跑，异步请求要先把响应挂起来
        return emitter;
    }

    /* ==================== 流式任务 ==================== */

    /**
     * 在流式线程池里执行一轮对话。
     *
     * <p>任何异常都必须在这里被收敛成一次 {@code error} 事件：
     * 异步请求已经与原始请求线程脱钩，异常若抛出去既不会被
     * {@code GlobalExceptionHandler} 捕获，也无法再改写成 HTTP 响应，
     * 只会留下一个永远不结束的连接。
     *
     * @param request  对话请求
     * @param userId   用户ID
     * @param listener SSE 推送器
     */
    private void runChat(ChatSendRequest request, long userId, SseStreamListener listener) {
        try {
            // 首帧下发会话ID，前端重连或刷新后据此确认续接的是同一段对话
            listener.start(request.sessionId());

            ChatReply reply = chatSupervisor.processStream(
                    request.sessionId(), userId, request.message(), listener);
            listener.complete(reply);

        } catch (BizException e) {
            log.warn("对话处理失败：session={} code={} message={}",
                    request.sessionId(), e.getCode(), e.getMessage());
            listener.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("对话处理异常：session={}", request.sessionId(), e);
            listener.fail(ResultCode.SYSTEM_ERROR.getCode(), "处理您的请求时出现异常，请稍后重试");
        }
    }

    /* ==================== 辅助 ==================== */

    /**
     * 取当前登录用户ID。
     *
     * <p>{@link com.mewchat.config.SecurityConfig} 已要求这些接口必须认证，
     * 因此正常流程里 principal 一定存在。这里仍然判空：万一将来有人放宽了路径规则，
     * 宁可返回 401，也不能把 null 当成某个用户继续跑下去。
     *
     * @param user 当前登录用户
     * @return 用户ID
     * @throws BizException 未认证时抛出
     */
    private long requireUserId(AuthenticatedUser user) {
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
        }
        return user.userId();
    }

    /**
     * 把消息实体映射成对外视图。
     *
     * @param message 消息实体
     * @return 对外视图
     */
    private ChatMessageView toView(Message message) {
        List<ChatMessageView.CitationView> citations =
                CollectionUtils.isEmpty(message.getRefDocs())
                        ? List.of()
                        : message.getRefDocs().stream().map(this::toCitation).toList();

        return new ChatMessageView(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreateTime(),
                message.getAgentName(),
                message.getConfidence(),
                citations);
    }

    /**
     * 把引用来源映射成对外视图。
     *
     * @param refDoc 引用来源
     * @return 对外视图
     */
    private ChatMessageView.CitationView toCitation(MessageRefDoc refDoc) {
        return new ChatMessageView.CitationView(
                refDoc.getChunkId(),
                refDoc.getDocTitle(),
                refDoc.getChunkNo(),
                refDoc.getScore());
    }
}
