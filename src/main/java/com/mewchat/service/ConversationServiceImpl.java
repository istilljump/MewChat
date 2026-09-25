package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.PendingClarification;
import com.mewchat.dao.mysql.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话业务服务实现。
 *
 * @author MewChat
 */
@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    /**
     * 会话状态：进行中。
     *
     * <p>public 不是给本类用的：统计装配器（{@code AdminStatsAssembler}）按状态分组计数，
     * 常量定义在两处迟早出现"服务层改了取值、仪表盘还按旧口径统计"的静默分叉 ——
     * 统计这类出错不报错的场景，只能靠口径单源来防。
     */
    public static final int STATUS_ACTIVE = 1;

    /** 会话状态：已结束，语义与取值见 {@link #STATUS_ACTIVE} 的说明 */
    public static final int STATUS_CLOSED = 2;

    /** 会话状态：已转人工（兜底建单后标记），语义与取值见 {@link #STATUS_ACTIVE} 的说明 */
    public static final int STATUS_HANDOFF = 3;

    /** 后台分页的默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 后台分页的每页条数上限，防止 {@code size=100000} 把整张表读进内存 */
    private static final int MAX_PAGE_SIZE = 200;

    /** "我的会话"列表的默认条数（侧边栏用） */
    private static final int DEFAULT_MINE_LIMIT = 30;

    /** "我的会话"列表的条数上限 */
    private static final int MAX_MINE_LIMIT = 100;

    /** 会话标题的长度上限（字符数），见 {@link #truncateTitle} */
    private static final int TITLE_MAX_LENGTH = 50;

    @Override
    public Conversation getBySessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        return lambdaQuery().eq(Conversation::getSessionId, sessionId).one();
    }

    @Override
    public Conversation getOrCreate(String sessionId, Long userId) {
        Conversation existing = getBySessionId(sessionId);
        if (existing != null) {
            return existing;
        }

        Conversation created = Conversation.builder()
                .sessionId(sessionId)
                .userId(userId)
                .title("")
                .status(STATUS_ACTIVE)
                .startTime(LocalDateTime.now())
                .messageCount(0)
                .build();

        // 并发下同一 sessionId 可能被两个请求同时创建：唯一键 uk_session_id 会让其中一个
        // 插入失败。这里必须自己接住这个异常并回查 —— 光写一句注释是接不住的，
        // DuplicateKeyException 会一路抛到调用方（用户看到的是"处理您的请求时出现了异常"，
        // 而另一条并发请求其实已经建好了会话）。回查后返回已存在的那条，调用方总能拿到会话。
        try {
            save(created);
        } catch (DuplicateKeyException e) {
            log.info("会话已被并发请求创建，回查已有记录：sessionId={}", sessionId);
        }

        Conversation reloaded = getBySessionId(sessionId);
        return reloaded != null ? reloaded : created;
    }

    @Override
    public void updateSummary(String sessionId, String summary) {
        lambdaUpdate()
                .eq(Conversation::getSessionId, sessionId)
                .set(Conversation::getSummary, summary)
                .update();
    }

    @Override
    public void touchOnNewMessage(String sessionId, LocalDateTime messageTime) {
        lambdaUpdate()
                .eq(Conversation::getSessionId, sessionId)
                // 用 SQL 表达式自增，避免"读出来加一再写回"在并发下丢计数
                .setSql("message_count = message_count + 1")
                .set(Conversation::getLastMessageTime, messageTime)
                // 重新激活：会话可能已被 SessionSummaryJob 按空闲超时收尾（status=2）。
                // 用户接着发消息时必须把它改回进行中并清掉结束时间，否则会有两个后果：
                //   ① 后台列表与统计里它一直显示"已结束"，而实际上还在对话；
                //   ② 收尾任务只扫描 status=1 的会话，这个会话的摘要（长时记忆）
                //      <b>再也不会被刷新</b>，Agent 会一直拿着收尾那一刻的旧摘要回答，
                //      对"超出短时窗口的早期对话"表现出记错内容。
                // 这里不做"仅当已关闭才改"的条件判断：进行中的会话写回同样的值是无害的，
                // 而多一条 UPDATE 条件反而多一处可能与实际状态不符的地方
                .set(Conversation::getStatus, STATUS_ACTIVE)
                .set(Conversation::getEndTime, null)
                .update();
    }

    @Override
    public void closeSession(String sessionId) {
        lambdaUpdate()
                .eq(Conversation::getSessionId, sessionId)
                .set(Conversation::getStatus, STATUS_CLOSED)
                .set(Conversation::getEndTime, LocalDateTime.now())
                .update();
    }

    @Override
    public List<Conversation> listIdleActiveSessions(LocalDateTime idleBefore, int limit) {
        if (idleBefore == null || limit <= 0) {
            return List.of();
        }
        // 括号不能省：nested 生成 (last_message_time < ? OR (last_message_time IS NULL AND start_time < ?))，
        // 少了括号会与 status 条件构成错误的优先级
        // maxLimit 必须显式给：分页插件默认套用全局单页上限(100)，
        // 批次大小一旦配成大于 100 就会被静默截断，表现为"收尾任务永远只处理前 100 个会话"
        Page<Conversation> page = Page.of(1, limit, false);
        page.setMaxLimit((long) limit);
        return page(page, Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getStatus, STATUS_ACTIVE)
                .nested(wrapper -> wrapper
                        .lt(Conversation::getLastMessageTime, idleBefore)
                        .or(inner -> inner
                                .isNull(Conversation::getLastMessageTime)
                                .lt(Conversation::getStartTime, idleBefore))))
                .getRecords();
    }

    @Override
    public Conversation createForUser(Long userId) {
        return getOrCreate(generateSessionId(), userId);
    }

    @Override
    public Conversation getOwnedBySessionId(String sessionId, Long userId) {
        Conversation conversation = getBySessionId(sessionId);
        if (conversation == null) {
            return null;
        }
        // 用 Objects.equals 而不是 !=：会话的 userId 允许为空（定时任务、游客会话），
        // 直接比较引用会把"两边都为空"这种正常情况判成不匹配
        return Objects.equals(conversation.getUserId(), userId) ? conversation : null;
    }

    @Override
    public Conversation resolveOwnedSession(String sessionId, Long userId) {
        Conversation existing = getBySessionId(sessionId);
        if (existing == null) {
            return getOrCreate(sessionId, userId);
        }
        if (!Objects.equals(existing.getUserId(), userId)) {
            // 先查后建之间存在并发窗口，但这里不构成安全问题：
            // 拿到会话的唯一途径是持有它对应的 ID，而 ID 在创建时就绑定了归属，
            // 并发下最坏的结果是"同一 ID 被建两次"，由唯一键兜住
            log.warn("会话归属校验失败：sessionId={} 请求方 userId={} 归属 userId={}",
                    sessionId, userId, existing.getUserId());
            throw new BizException(ResultCode.FORBIDDEN, SESSION_UNAVAILABLE_MESSAGE);
        }
        return existing;
    }

    @Override
    public Page<Conversation> pageConversations(Long userId, Integer status, int pageNo, int pageSize) {
        return page(Page.of(Math.max(1, pageNo), clampPageSize(pageSize)),
                Wrappers.<Conversation>lambdaQuery()
                        .eq(userId != null, Conversation::getUserId, userId)
                        .eq(status != null, Conversation::getStatus, status)
                        // 最近活跃的排前面：客服按会话查问题时，最新的一定是最相关的
                        .orderByDesc(Conversation::getLastMessageTime)
                        .orderByDesc(Conversation::getId));
    }

    @Override
    public List<Conversation> listMine(Long userId, int limit) {
        if (userId == null) {
            // 没登录就没有"我的会话"。返回空列表而不是抛异常：这个方法的调用方
            // （对话接口）已经由安全链保证了登录，真出现 null 是编程错误，
            // 但让列表为空比让整个页面报错更符合"查不到"的语义
            return List.of();
        }
        return page(Page.of(1, clampMineLimit(limit)),
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getUserId, userId)
                        // 空会话不进列表：前端每次点"新对话"都会先建一条会话，
                        // 不过滤的话侧边栏会被"建了却没说一句话"的记录刷满
                        .gt(Conversation::getMessageCount, 0)
                        // 与会话列表同一个排序口径：最近活跃的排前面，
                        // 且 NULL 活跃时间（刚建、还没说话）排最后 —— 前端要的正是这个顺序
                        .orderByDesc(Conversation::getLastMessageTime)
                        .orderByDesc(Conversation::getId))
                .getRecords();
    }

    @Override
    public void updateTitleIfBlank(String sessionId, String title) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(title)) {
            return;
        }
        // 条件更新而不是先查后写：标题只写一次（首条用户消息），
        // 把它写成 UPDATE 的 WHERE 条件，并发发两条消息时也只有一条能写进去
        lambdaUpdate()
                .eq(Conversation::getSessionId, sessionId)
                .and(wrapper -> wrapper.eq(Conversation::getTitle, "").or().isNull(Conversation::getTitle))
                .set(Conversation::getTitle, truncateTitle(title))
                .update();
    }

    /**
     * 截断标题到列宽以内（{@code title} 为 VARCHAR(100)）。
     *
     * <p>按字符截断而不是按字节：中文一个字占多个字节，按字节截会把最后一个字切碎。
     * 留 3 个字符的余量给可能的省略号与空白。
     *
     * @param title 原始标题（首条用户消息）
     * @return 截断后的标题
     */
    private static String truncateTitle(String title) {
        String trimmed = title.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    /**
     * 收敛"我的会话"条数上限。
     *
     * <p>侧边栏只展示最近若干条，不需要分页；上限必须存在，
     * 否则累计了上千个会话的账号每次打开页面都会把全部会话读出来。
     *
     * @param limit 请求条数
     * @return 合法条数
     */
    private static int clampMineLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_MINE_LIMIT;
        }
        return Math.min(limit, MAX_MINE_LIMIT);
    }

    /**
     * 收敛每页条数。
     *
     * <p>必须封顶：{@code size=100000} 会把整张表读进内存，一个请求就能压垮服务。
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

    @Override
    public PendingClarification getPendingClarification(String sessionId) {
        Conversation conversation = getBySessionId(sessionId);
        return conversation == null ? null : conversation.getPendingClarification();
    }

    @Override
    public void savePendingClarification(String sessionId, PendingClarification pending) {
        writePendingClarification(sessionId, pending);
    }

    @Override
    public void clearPendingClarification(String sessionId) {
        writePendingClarification(sessionId, null);
    }

    /**
     * 写入挂起状态，{@code pending} 为 null 时表示清空。
     *
     * <p><b>为什么用 {@code updateById} 带一个只含主键的实体，而不是
     * {@code lambdaUpdate().set(...)}</b>：JSON 列的写入依赖字段上标注的
     * {@code JacksonTypeHandler}，而它只在"走实体字段"的语句里生效；
     * 用 update wrapper 的 {@code set(列, 值)} 需要额外用字符串参数手写 typeHandler，
     * 一旦漏掉，写进去的就是一个无法序列化的对象（直接报错）。
     * 传实体则沿用既有的映射信息，同时借 {@code FieldStrategy.ALWAYS}
     * 把"置空"这件事也表达出来 —— 默认策略会跳过 null 字段，导致清空语句里根本没有这一列。
     *
     * @param sessionId 会话业务ID
     * @param pending   挂起状态，null 表示清空
     */
    private void writePendingClarification(String sessionId, PendingClarification pending) {
        Conversation existing = getBySessionId(sessionId);
        if (existing == null) {
            log.warn("会话不存在，跳过挂起状态写入：session={}", sessionId);
            return;
        }
        Conversation patch = new Conversation();
        patch.setId(existing.getId());
        patch.setPendingClarification(pending);
        updateById(patch);
    }

    /**
     * 生成会话业务ID。
     *
     * <p>用 UUID 去掉连字符：32 位十六进制，碰撞概率可忽略，且不可预测 ——
     * 会话ID 同时充当访问凭证（见接口注释），可预测的ID等于把别人的对话敞开。
     *
     * @return 会话业务ID
     */
    private static String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
