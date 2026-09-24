package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.dao.mysql.entity.User;
import com.mewchat.dao.mysql.mapper.TicketMapper;
import com.mewchat.dao.mysql.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 人工工单业务服务。
 *
 * <p>两个方向的能力：
 * <ul>
 *     <li><b>自动建单</b>（{@link #createFallbackTicket}）：Agent 兜底时落单，
 *         保证"答不上来"的问题有人接手</li>
 *     <li><b>后台处理</b>（{@link #pageTickets} / {@link #assign} /
 *         {@link #resolve} / {@link #close}）：客服工作台查单、派单、结单</li>
 * </ul>
 *
 * <p><b>为什么"同一会话只留一张未关闭工单"</b>：用户遇到答不上的问题时，
 * 往往会换个说法连问几次。每问一次建一张单，客服工作台就会被同一个会话刷屏，
 * 真正待处理的其他问题反而被淹掉。一个会话只要有一张未关闭的工单就足够了，
 * 追问细节都记在消息记录里。因此建单是<b>幂等</b>的：已存在则复用。
 *
 * <p>继承 {@code ServiceImpl} 以复用 MyBatis-Plus 的分页、条件更新与计数能力
 * （与本项目其它业务服务保持一致），因此不再单独注入 Mapper。
 *
 * @author MewChat
 */
@Service
public class TicketService extends ServiceImpl<TicketMapper, Ticket> {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** 工单状态：待处理 */
    private static final int STATUS_PENDING = 0;

    /** 工单状态：处理中 */
    private static final int STATUS_PROCESSING = 1;

    /** 工单状态：已解决 */
    private static final int STATUS_RESOLVED = 2;

    /** 工单状态：已关闭 */
    private static final int STATUS_CLOSED = 3;

    /** 后台分页的默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 后台分页的每页条数上限。
     *
     * <p>必须封顶：{@code size=100000} 这种请求会把整张表读进内存，
     * 一个请求就能把服务拖垮。上限的存在比"相信调用方会守规矩"可靠。
     */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 兜底自动建单的工单类型。
     *
     * <p>统一记为 {@code other}：更细的类型（退款/物流/商品）需要按意图区分，
     * 而意图是编排层的概念，本服务不该认识它。等客服工作台真的需要按类型分派时，
     * 由编排层把类型映射好再传进来 —— 那时改这一个方法签名即可。
     */
    private static final String TYPE_OTHER = "other";

    /**
     * 建单幂等用的分段锁数量。
     *
     * <p>按 {@code sessionId} 的哈希取模选一把锁（而不是每个会话一个锁对象）：
     * 会话数量无上限，逐个建锁会让锁本身成为内存泄漏。分段数取 64 ——
     * 不同会话撞到同一把锁只是多等一次内存里的查询，代价可忽略。
     */
    private static final int LOCK_STRIPES = 64;

    /** 建单幂等的分段锁，见 {@link #createFallbackTicket} */
    private final Object[] ticketLocks = new Object[LOCK_STRIPES];

    /** 校验工单处理人是否真实存在且具备客服/管理员身份，见 {@link #requireAssignableHandler} */
    private final UserMapper userMapper;

    public TicketService(UserMapper userMapper) {
        this.userMapper = userMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            ticketLocks[i] = new Object();
        }
    }

    /**
     * 为一次兜底创建工单；同一会话已有未关闭工单时复用，不重复创建。
     *
     * @param sessionId  会话业务ID
     * @param userId     提单用户ID。<b>为空时直接跳过</b>（见下）
     * @param question   用户问题，用于工单描述，供人工接手时快速了解背景
     * @param confidence 本轮置信度，便于人工判断"是知识缺失还是理解错了"
     * @return 工单ID；未创建（无用户或入参不合法）时返回 null
     */
    public Long createFallbackTicket(String sessionId, Long userId, String question, BigDecimal confidence) {
        if (userId == null) {
            // 表定义里 user_id 是 NOT NULL —— 工单必须有提单人，否则客服接手时无从联系。
            // 游客会话（未登录）因此不建单：这类会话本来就拿不到用户身份，
            // 而对话接口已要求认证，正常链路上这里不会为空
            log.warn("兜底会话没有用户ID，跳过建单：session={}", sessionId);
            return null;
        }
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(question)) {
            log.warn("兜底建单入参不合法，跳过：session={} 问题是否为空={}",
                    sessionId, !StringUtils.hasText(question));
            return null;
        }

        Long existing = findOpenTicketId(sessionId);
        if (existing != null) {
            log.debug("会话已有未关闭工单，复用：session={} ticketId={}", sessionId, existing);
            return existing;
        }

        // 幂等必须在锁内闭环。上面那次查与下面的插入是 check-then-act：
        // 用户重连、客户端重试、SSE 断线重发都可能让同一会话的兜底并发跑两次，
        // 两个线程各自查到"没有未关闭工单"然后各插一张 —— 客服工作台立刻被同一会话刷屏，
        // 正是幂等要避免的那件事。
        //
        // 表上没有能表达"同一会话只允许一张未关闭工单"的唯一键（MySQL 不支持条件唯一索引，
        // 而 (session_id, status) 唯一键会把"同一会话关过两张单"也一起禁掉），
        // 所以这里用按会话分段的进程内锁保证<b>单实例内</b>的幂等。
        // 多实例部署时仍需数据库层兜底（生成列 + 唯一索引）或分布式锁，已记在 AGENTS.md。
        Object lock = ticketLocks[Math.floorMod(sessionId.hashCode(), LOCK_STRIPES)];
        synchronized (lock) {
            Long raced = findOpenTicketId(sessionId);
            if (raced != null) {
                log.debug("并发兜底：另一个请求已建单，复用：session={} ticketId={}", sessionId, raced);
                return raced;
            }

            Ticket ticket = Ticket.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .type(TYPE_OTHER)
                    .description(buildDescription(sessionId, question, confidence))
                    .status(STATUS_PENDING)
                    .build();
            save(ticket);

            log.info("兜底自动建单：session={} userId={} ticketId={} confidence={}",
                    sessionId, userId, ticket.getId(), confidence);
            return ticket.getId();
        }
    }

    /* ==================== 后台工作台 ==================== */

    /**
     * 分页查询工单。
     *
     * @param status   状态过滤，为 null 表示不筛选
     * @param pageNo   页码，从 1 开始；非法值会被兜到 1
     * @param pageSize 每页条数；非法值会被兜到默认值
     * @return 分页结果
     */
    public Page<Ticket> pageTickets(Integer status, int pageNo, int pageSize) {
        return page(Page.of(Math.max(1, pageNo), clampPageSize(pageSize)),
                Wrappers.<Ticket>lambdaQuery()
                        .eq(status != null, Ticket::getStatus, status)
                        // 新的排在最前：客服工作台要的是"先来先处理"
                        .orderByDesc(Ticket::getCreateTime));
    }

    /**
     * 指派工单：登记处理人并把状态推进到"处理中"。
     *
     * <p>不允许指派已解决/已关闭的工单：那类工单的责任已经落定，
     * 再挂上一个人会让人以为它还没人管。
     *
     * @param ticketId  工单ID
     * @param handlerId 处理人ID（客服或管理员）
     * @return 更新后的工单
     * @throws BizException 工单不存在、已结束，或未提供处理人时抛出
     */
    public Ticket assign(Long ticketId, Long handlerId) {
        if (handlerId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "处理人不能为空");
        }
        requireAssignableHandler(handlerId);
        Ticket ticket = requireOpenTicket(ticketId, "指派");

        lambdaUpdate()
                .eq(Ticket::getId, ticket.getId())
                .set(Ticket::getHandlerId, handlerId)
                .set(Ticket::getStatus, STATUS_PROCESSING)
                .update();

        log.info("工单已指派：ticketId={} handlerId={}", ticketId, handlerId);
        return getById(ticketId);
    }

    /**
     * 标记工单已解决。
     *
     * @param ticketId 工单ID
     * @return 更新后的工单
     * @throws BizException 工单不存在或已关闭时抛出
     */
    public Ticket resolve(Long ticketId) {
        return finish(ticketId, STATUS_RESOLVED, "已解决");
    }

    /**
     * 关闭工单。
     *
     * @param ticketId 工单ID
     * @return 更新后的工单
     * @throws BizException 工单不存在或已关闭时抛出
     */
    public Ticket close(Long ticketId) {
        return finish(ticketId, STATUS_CLOSED, "已关闭");
    }

    /**
     * 把工单推进到终态之一。
     *
     * <p>已关闭的工单不允许再改状态：关闭通常意味着"这条不再需要跟进"，
     * 允许反复改会让工单的最终状态失去意义。已解决的工单仍可关闭
     * （解决是"处理完了"，关闭是"归档了"）。
     *
     * @param ticketId   工单ID
     * @param target     目标状态
     * @param targetName 目标状态中文名，用于日志
     * @return 更新后的工单
     * @throws BizException 工单不存在或已关闭时抛出
     */
    private Ticket finish(Long ticketId, int target, String targetName) {
        Ticket ticket = requireTicket(ticketId);
        if (Objects.equals(ticket.getStatus(), STATUS_CLOSED)) {
            throw new BizException(ResultCode.PARAM_INVALID, "工单已关闭，不能再变更状态");
        }

        lambdaUpdate()
                .eq(Ticket::getId, ticketId)
                .set(Ticket::getStatus, target)
                .set(Ticket::getFinishTime, LocalDateTime.now())
                .update();

        log.info("工单状态变更：ticketId={} -> {}（{}）", ticketId, target, targetName);
        return getById(ticketId);
    }

    /* ==================== 内部方法 ==================== */

    /**
     * 取出工单并要求它未结束。
     *
     * @param ticketId 工单ID
     * @param action   操作名，仅用于错误提示
     * @return 工单
     * @throws BizException 工单不存在或已结束时抛出
     */
    private Ticket requireOpenTicket(Long ticketId, String action) {
        Ticket ticket = requireTicket(ticketId);
        boolean finished = Objects.equals(ticket.getStatus(), STATUS_RESOLVED)
                || Objects.equals(ticket.getStatus(), STATUS_CLOSED);
        if (finished) {
            throw new BizException(ResultCode.PARAM_INVALID, "工单已结束，不能再" + action);
        }
        return ticket;
    }

    /**
     * 取出工单，不存在时抛业务异常。
     *
     * @param ticketId 工单ID
     * @return 工单
     * @throws BizException 工单不存在时抛出
     */
    private Ticket requireTicket(Long ticketId) {
        if (ticketId == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "工单ID不能为空");
        }
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new BizException(ResultCode.NOT_FOUND, "工单不存在：" + ticketId);
        }
        return ticket;
    }

    /**
     * 收敛每页条数，防止调用方用 {@code size=100000} 把数据库拖死。
     *
     * @param pageSize 请求的每页条数
     * @return 合法条数
     */
    private static int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 查询该会话未关闭的工单ID。
     *
     * <p>"未关闭"取待处理与处理中两种状态：已解决/已关闭的都算处理完了，
     * 用户再遇到新问题应当另起一张单。
     *
     * @param sessionId 会话业务ID
     * @return 工单ID；没有则返回 null
     */
    private Long findOpenTicketId(String sessionId) {
        List<Ticket> open = list(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getSessionId, sessionId)
                .in(Ticket::getStatus, List.of(STATUS_PENDING, STATUS_PROCESSING))
                .orderByDesc(Ticket::getCreateTime));
        return open.isEmpty() ? null : open.get(0).getId();
    }

    /**
     * 校验处理人确实是客服或管理员。
     *
     * <p><b>为什么必须查一次库</b>：{@code handlerId} 是后台请求里的一个裸参数。
     * 不校验的话，一个打错的ID（甚至某个客户的ID）也会让工单变成"处理中" ——
     * 它从"待处理"队列里消失、看起来有人管了，实际没有任何客服接手；
     * 而将来若加上"我的工单"视图（按 handlerId 查询），客户的ID会把别人的工单
     * 显示到他的列表里。这类错误不报错、只在业务上悄悄变坏，正是最该拦住的那种。
     *
     * <p>用户类型判定与 {@code AuthenticatedUser} 的取值保持一致（2 客服 / 3 管理员）；
     * 被逻辑删除的用户查不出来，同样会被拒。
     *
     * @param handlerId 处理人ID
     * @throws BizException 用户不存在或不是客服/管理员时抛出
     */
    private void requireAssignableHandler(Long handlerId) {
        User handler = userMapper.selectById(handlerId);
        if (handler == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "处理人不存在：" + handlerId);
        }
        Integer userType = handler.getUserType();
        if (!Objects.equals(userType, AuthenticatedUser.TYPE_AGENT)
                && !Objects.equals(userType, AuthenticatedUser.TYPE_ADMIN)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                    "该用户不是客服或管理员，不能作为工单处理人：" + handlerId);
        }
    }

    /**
     * 构造工单描述。
     *
     * <p>写明是自动建的、以及当时置信度多少：人工看到"置信度 0.00"就知道
     * 是知识库里完全没有相关内容，看到"0.55"则更可能是问题表述不清或知识库有旧版本，
     * 两种情况的处理方式完全不同。
     *
     * @param sessionId  会话业务ID
     * @param question   用户问题
     * @param confidence 置信度
     * @return 工单描述
     */
    private String buildDescription(String sessionId, String question, BigDecimal confidence) {
        return "【智能客服自动建单】用户问题：" + question
                + "；本轮置信度：" + (confidence == null ? "未知" : confidence.toPlainString())
                + "；会话ID：" + sessionId
                + "（可在客服工作台按会话ID查看完整对话记录）";
    }
}
