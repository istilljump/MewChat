package com.mewchat.api.admin;

import com.mewchat.api.admin.dto.AdminMessageView;
import com.mewchat.api.admin.dto.ConversationView;
import com.mewchat.api.admin.dto.PageView;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.service.ConversationService;
import com.mewchat.service.MessageService;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话记录查询接口（运营后台）。
 *
 * <p><b>这是整个系统里权限最敏感的接口</b>：它能让调用方读到<b>任意用户</b>的完整对话，
 * 包含订单号、收货地址等个人信息。因此：
 * <ul>
 *     <li>路径在 {@code /api/admin/**} 下，由 {@code SecurityConfig} 限定为仅管理员</li>
 *     <li>服务层<b>刻意不做归属校验</b> —— 客服要能按工单里的 sessionId 查进对话，
 *         否则工单无法处理。权限靠角色把关，而不是靠"只给自己看"</li>
 * </ul>
 * 两句话放在一起看：能读到别人的对话是有意为之，所以角色校验一旦失效，
 * 泄露面就是全量数据。改动这个接口时务必连带检查安全配置。
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/admin/conversations")
public class ConversationAdminController {

    private final ConversationService conversationService;

    private final MessageService messageService;

    public ConversationAdminController(ConversationService conversationService,
                                       MessageService messageService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    /**
     * 分页查询会话。
     *
     * @param userId 用户ID过滤，可为空
     * @param status 状态过滤，可为空
     * @param page   页码
     * @param size   每页条数
     * @return 分页结果
     */
    @GetMapping
    public Result<PageView<ConversationView>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(PageView.of(
                conversationService.pageConversations(userId, status, page, size), this::toView));
    }

    /**
     * 查询某个会话的完整对话记录。
     *
     * @param sessionId 会话业务ID
     * @return 消息列表，时间正序
     */
    @GetMapping("/{sessionId}/messages")
    public Result<List<AdminMessageView>> messages(@PathVariable String sessionId) {
        // 先确认会话存在：会话ID写错时应当明确报"不存在"，
        // 而不是返回一个空列表让人以为"这轮对话真的没说过话"
        if (conversationService.getBySessionId(sessionId) == null) {
            return Result.error(ResultCode.NOT_FOUND, "会话不存在：" + sessionId);
        }

        List<AdminMessageView> views = messageService.listAllBySessionId(sessionId).stream()
                .map(this::toView)
                .toList();
        return Result.success(views);
    }

    /**
     * 把会话实体映射成对外视图。
     *
     * @param conversation 会话实体
     * @return 视图
     */
    private ConversationView toView(Conversation conversation) {
        return new ConversationView(
                conversation.getId(),
                conversation.getSessionId(),
                conversation.getUserId(),
                conversation.getTitle(),
                conversation.getStatus(),
                ConversationView.statusLabel(conversation.getStatus()),
                conversation.getMessageCount(),
                conversation.getSummary(),
                // "用户还卡在一个没答完的追问上"对客服是有用信号，因此单独给出
                conversation.getPendingClarification() != null,
                conversation.getStartTime(),
                conversation.getEndTime(),
                conversation.getLastMessageTime());
    }

    /**
     * 把消息实体映射成对外视图。
     *
     * @param message 消息实体
     * @return 视图
     */
    private AdminMessageView toView(Message message) {
        List<AdminMessageView.Citation> citations = CollectionUtils.isEmpty(message.getRefDocs())
                ? List.of()
                : message.getRefDocs().stream().map(this::toCitation).toList();

        return new AdminMessageView(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getAgentName(),
                message.getConfidence(),
                message.getTotalTokens(),
                message.getCostMs(),
                message.getStatus(),
                message.getErrorMsg(),
                citations,
                message.getCreateTime());
    }

    /**
     * 把引用来源映射成对外视图。
     *
     * @param refDoc 引用来源
     * @return 视图
     */
    private AdminMessageView.Citation toCitation(MessageRefDoc refDoc) {
        return new AdminMessageView.Citation(
                refDoc.getChunkId(), refDoc.getDocTitle(), refDoc.getChunkNo());
    }
}
