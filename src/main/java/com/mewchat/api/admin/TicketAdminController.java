package com.mewchat.api.admin;

import com.mewchat.api.admin.dto.PageView;
import com.mewchat.api.admin.dto.TicketView;
import com.mewchat.common.result.Result;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单处理接口（运营后台）。
 *
 * <p>覆盖客服工作台的核心动作：<b>查单 → 指派 → 结单</b>。
 * 工单由 Agent 兜底时自动创建（见 {@code TicketService.createFallbackTicket}），
 * 人工这一侧需要的就是接手与收尾。
 *
 * <p><b>状态流转的约束在服务层</b>：已结束的工单不能再指派或重复结单，
 * 这条规则写在 {@code TicketService} 里而不是这里 —— 将来若新增一个
 * "批量指派"的入口，规则就不会因为漏改而失效。
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/admin/tickets")
public class TicketAdminController {

    private static final Logger log = LoggerFactory.getLogger(TicketAdminController.class);

    private final TicketService ticketService;

    public TicketAdminController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 分页查询工单。
     *
     * @param status 状态过滤，可为空
     * @param page   页码
     * @param size   每页条数
     * @return 分页结果
     */
    @GetMapping
    public Result<PageView<TicketView>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(PageView.of(ticketService.pageTickets(status, page, size), this::toView));
    }

    /**
     * 当前登录人名下的工单（客服工作台的"我的工单"视图）。
     *
     * <p><b>处理人来自令牌而不是请求参数</b>：工作台列表是"我的"队列，
     * 把 handlerId 放开成参数等于允许任何能进工作台的账号冒别人的身份翻看队列。
     * 典型用法是 {@code status=1}（处理中）看自己手头未结的单。
     *
     * @param user   当前登录的客服/管理员（来自令牌）
     * @param status 状态过滤，可为空
     * @param page   页码
     * @param size   每页条数
     * @return 分页结果
     */
    @GetMapping("/my")
    public Result<PageView<TicketView>> myTickets(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(
                PageView.of(ticketService.pageMyTickets(user.userId(), status, page, size), this::toView));
    }

    /**
     * 接单：把一张待处理工单认领给当前登录人。
     *
     * <p>与"指派"互补：指派是管理员的安排动作（处理人来自参数），
     * 接单是客服的自助动作（处理人就是令牌里的自己）。只有待处理工单能接，
     * 已被同事接走的单不会、也不该被"抢"。
     *
     * @param ticketId 工单ID
     * @param user     当前登录的客服/管理员（来自令牌）
     * @return 更新后的工单
     */
    @PostMapping("/{ticketId}/claim")
    public Result<TicketView> claim(@PathVariable Long ticketId,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return Result.success(toView(ticketService.claim(ticketId, user.userId())));
    }

    /**
     * 指派工单给某个客服。
     *
     * @param ticketId  工单ID
     * @param handlerId 处理人ID
     * @return 更新后的工单
     */
    @PostMapping("/{ticketId}/assign")
    public Result<TicketView> assign(@PathVariable Long ticketId,
                                     @RequestParam Long handlerId) {
        return Result.success(toView(ticketService.assign(ticketId, handlerId)));
    }

    /**
     * 标记工单已解决。
     *
     * @param ticketId 工单ID
     * @return 更新后的工单
     */
    @PostMapping("/{ticketId}/resolve")
    public Result<TicketView> resolve(@PathVariable Long ticketId) {
        return Result.success(toView(ticketService.resolve(ticketId)));
    }

    /**
     * 关闭工单。
     *
     * @param ticketId 工单ID
     * @return 更新后的工单
     */
    @PostMapping("/{ticketId}/close")
    public Result<TicketView> close(@PathVariable Long ticketId) {
        log.info("后台关闭工单：ticketId={}", ticketId);
        return Result.success(toView(ticketService.close(ticketId)));
    }

    /**
     * 把工单实体映射成对外视图。
     *
     * @param ticket 工单实体
     * @return 视图
     */
    private TicketView toView(Ticket ticket) {
        return new TicketView(
                ticket.getId(),
                ticket.getSessionId(),
                ticket.getUserId(),
                ticket.getType(),
                ticket.getDescription(),
                ticket.getStatus(),
                TicketView.statusLabel(ticket.getStatus()),
                ticket.getHandlerId(),
                ticket.getFinishTime(),
                ticket.getCreateTime());
    }
}
