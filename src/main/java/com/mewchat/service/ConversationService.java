package com.mewchat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.PendingClarification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话业务服务。
 *
 * <p>位于编排层与数据访问层之间：{@code agent} 与 {@code api} 只能通过本接口
 * 操作会话数据，不允许直接注入 {@code ConversationMapper}。
 *
 * @author MewChat
 */
public interface ConversationService extends IService<Conversation> {

    /**
     * 按会话业务ID查询会话。
     *
     * @param sessionId 会话业务ID
     * @return 会话，不存在时返回 null
     */
    Conversation getBySessionId(String sessionId);

    /**
     * 获取会话，不存在则创建。
     *
     * <p>对话接口每次调用都会调它，因此必须是幂等的。
     *
     * @param sessionId 会话业务ID
     * @param userId    用户ID，游客会话传 null
     * @return 已存在的或新建的会话
     */
    Conversation getOrCreate(String sessionId, Long userId);

    /**
     * 更新会话摘要（长时记忆）。
     *
     * @param sessionId 会话业务ID
     * @param summary   摘要内容
     */
    void updateSummary(String sessionId, String summary);

    /**
     * 记录一条新消息带来的会话变化。
     *
     * <p>维护 {@code messageCount} 与 {@code lastMessageTime} 两个冗余字段，
     * 让会话列表查询不必对消息表做聚合。
     *
     * @param sessionId   会话业务ID
     * @param messageTime 本次消息时间
     */
    void touchOnNewMessage(String sessionId, LocalDateTime messageTime);

    /**
     * 结束会话。
     *
     * @param sessionId 会话业务ID
     */
    void closeSession(String sessionId);

    /**
     * 查询长时间无新消息的进行中会话。
     *
     * <p>供定时任务使用：会话"结束"没有显式信号（用户直接关掉页面就走了），
     * 只能以"多久没说话"作为判据。空闲超时后关闭会话，才能生成摘要（长时记忆）。
     *
     * <p>没有任何消息的会话按会话开始时间判断，否则它们会永远停留在进行中。
     *
     * @param idleBefore 空闲截止时间，早于该时间无消息的会话即视为已结束
     * @param limit      单批处理条数上限，避免一次捞出过多导致长事务
     * @return 待关闭的会话列表
     */
    List<Conversation> listIdleActiveSessions(LocalDateTime idleBefore, int limit);

    /**
     * 为指定用户新建一个会话。
     *
     * <p><b>会话ID由本方法生成，不接受调用方传入</b>：会话ID 实际承担着"访问凭证"
     * 的作用（拿到它就能读到这段对话），因此必须唯一且不可预测。
     * 若交给每个调用方自己造（有人用时间戳、有人用自增数），
     * 只要有一处可被枚举，别人的对话就能被翻出来。
     *
     * @param userId 用户ID
     * @return 新建的会话
     */
    Conversation createForUser(Long userId);

    /**
     * 按会话ID查询会话，并校验归属。
     *
     * <p><b>不存在与"不属于该用户"都返回 null</b>，是刻意的：
     * 若两种情况返回不同结果，就等于告诉了调用方"这个会话ID确实存在，只是不归你"，
     * 可以被用来枚举他人的会话。调用方对两者应给出同样的响应。
     *
     * @param sessionId 会话业务ID
     * @param userId    用户ID
     * @return 会话；不存在或不属于该用户时返回 null
     */
    Conversation getOwnedBySessionId(String sessionId, Long userId);

    /**
     * 会话不可用时的统一提示。
     *
     * <p>刻意<b>不区分"会话不存在"与"会话不属于你"</b>：若能区分，
     * 就等于告诉调用方"这个ID确实存在、只是不归你"，可被用来枚举他人的会话ID。
     * 会话ID 一旦泄露就等于对话内容泄露，因此这里连存在性都不透露。
     */
    String SESSION_UNAVAILABLE_MESSAGE = "会话不存在或无权访问";

    /**
     * 解析会话：不存在则创建，存在则校验归属。
     *
     * <p>对话接口的入口校验。之所以把"创建"与"校验"合成一个方法：发送消息时
     * 无法预先知道 sessionId 是新是旧（前端复用同一个ID续接对话，也可能是刚建的），
     * 拆开写会变成"先查、不存在再建、存在再校验"三段分支，
     * 而漏掉校验那一步的后果很严重 —— 回复提示词里带着该会话的历史，
     * 等于把别人的对话读给当前用户听。
     *
     * @param sessionId 会话业务ID
     * @param userId    当前用户ID
     * @return 会话
     * @throws com.mewchat.common.exception.BizException 会话存在但不属于该用户时抛出
     */
    Conversation resolveOwnedSession(String sessionId, Long userId);

    /**
     * 分页查询会话（运营后台用）。
     *
     * <p><b>刻意不做归属校验</b>：客服要能查到任意用户的会话，
     * 否则工单里写着"sessionId=xxx"而客服点不进去，工单就没法处理。
     * 权限由 {@code SecurityConfig} 的 {@code /api/admin/**} 规则把关（仅管理员），
     * 而不是在这一层按用户过滤 —— 两个口子的鉴权方式不同，是刻意分开的。
     *
     * @param userId   用户ID过滤，为 null 表示不筛选
     * @param status   状态过滤，为 null 表示不筛选
     * @param pageNo   页码，从 1 开始；非法值兜到 1
     * @param pageSize 每页条数；非法值兜到默认值，且有上限
     * @return 分页结果
     */
    Page<Conversation> pageConversations(Long userId, Integer status, int pageNo, int pageSize);

    /**
     * 查询某个用户最近的会话（对话页侧边栏"会话记录"用）。
     *
     * <p>与 {@link #pageConversations} 的区别：那个方法是运营后台的口子（不校验归属、
     * 由接口层权限把关），这里是<b>面向用户自己</b>的列表，只看得到自己的会话 ——
     * 归属由调用方传入的 userId 决定，接口层从令牌里取，不接受请求参数。
     *
     * <p>按最近活跃时间倒序；从未说过话的会话（活跃时间为空）排在最后。
     *
     * @param userId 当前用户ID
     * @param limit  条数上限，非法值兜到默认值，且封顶（见实现）
     * @return 会话列表，可能为空
     */
    List<Conversation> listMine(Long userId, int limit);

    /**
     * 会话标题为空时写入标题（首条用户消息即标题）。
     *
     * <p>只写一次：标题跟着会话走，"更聪明"的做法是让模型总结话题，
     * 但那要多付一次模型调用、且用户看到的标题会随时间突变。
     * 用首句原话既便宜又稳定，也是建表时注释里写的方案。
     *
     * @param sessionId 会话业务ID
     * @param title     候选标题（会做长度截断）
     */
    void updateTitleIfBlank(String sessionId, String title);

    /**
     * 读取挂起的澄清追问。
     *
     * @param sessionId 会话业务ID
     * @return 挂起状态；不存在或没有待澄清项时返回 null
     */
    PendingClarification getPendingClarification(String sessionId);

    /**
     * 写入挂起的澄清追问。
     *
     * <p>由编排层在追问里给出候选项时调用：用户下一轮回复的序号要靠它才能还原成具体订单号。
     *
     * @param sessionId 会话业务ID
     * @param pending   挂起状态
     */
    void savePendingClarification(String sessionId, PendingClarification pending);

    /**
     * 清除挂起的澄清追问。
     *
     * <p>用户一旦作了别的选择（问了个新问题、或者选中了某个候选），
     * 这条挂起状态就失效了 —— 留着它，用户之后随口回一个"1"
     * 会被续接回一个早已过时的追问。
     *
     * @param sessionId 会话业务ID
     */
    void clearPendingClarification(String sessionId);
}
