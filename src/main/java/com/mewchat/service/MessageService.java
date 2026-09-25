package com.mewchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mewchat.dao.mysql.entity.Message;

import java.util.List;
import java.util.Map;

/**
 * 消息业务服务。
 *
 * @author MewChat
 */
public interface MessageService extends IService<Message> {

    /**
     * 查询某个会话最近若干条消息，按时间正序返回。
     *
     * <p>这是短时记忆的数据来源：不需要在内存里维护滑动窗口，
     * 每次直接从库里取最近 N 条即可 —— 服务无状态，重启与多实例部署都不丢上下文。
     *
     * @param sessionId 会话业务ID
     * @param limit     最大条数
     * @return 消息列表，时间正序（最早的在前）
     */
    List<Message> listRecentBySessionId(String sessionId, int limit);

    /**
     * 查询某个会话的全部消息，按时间正序返回。
     *
     * <p>生成会话摘要时使用，因此调用方需要自己控制条数上限。
     *
     * @param sessionId 会话业务ID
     * @return 消息列表，时间正序
     */
    List<Message> listAllBySessionId(String sessionId);

    /* ==================== 用户反馈（点赞/点踩） ==================== */

    /**
     * 反馈：这条回答有用。
     *
     * <p>public 是为了让接口层把实体里的数值映射成对外的 up/down 时引用同一份定义，
     * 就像工单状态那样只有一处取值来源。
     */
    int FEEDBACK_UP = 1;

    /** 反馈：这条回答没用，语义与取值见 {@link #FEEDBACK_UP} 的说明 */
    int FEEDBACK_DOWN = 2;

    /**
     * 记录用户对某条助手回答的反馈（点赞 / 点踩）。
     *
     * <p><b>必须校验归属，且校验方式与会话读取一致</b>：消息ID 是雪花ID，
     * 但它和会话ID 一样被别人拿到就等于拿到了对话内容的一部分。
     * 因此这里沿消息 → 会话 → 用户这条链校验，不通过时返回与会话历史<b>同样口径</b>的
     * "不存在或无权访问"（不区分"消息不存在"与"不是你的消息"，否则可被用来枚举他人的消息）。
     *
     * <p><b>只允许对助手消息反馈</b>：用户消息没有"有用/没用"的语义，
     * 放开只会让统计里混进一半无意义的数据。
     *
     * <p><b>可改主意</b>：重复反馈覆盖前一次（保留最后一次选择与时间）。
     *
     * @param messageId 消息ID
     * @param userId    当前用户ID
     * @param vote      反馈值，取值见 {@link #FEEDBACK_UP} / {@link #FEEDBACK_DOWN}
     * @return 更新后的消息
     * @throws com.mewchat.common.exception.BizException 消息不存在、不属于该用户、
     *                                                   不是助手消息，或反馈值非法时抛出
     */
    Message recordFeedback(Long messageId, Long userId, int vote);

    /**
     * 批量取多个会话的"首个用户提问"，用于给没有标题的历史会话兜底显示。
     *
     * <p>只读、不写库：这些会话建在"首句即标题"上线之前，标题是空的。
     * 与其把它们显示成"（无标题）"，不如用用户自己问的第一句话做预览 ——
     * 但<b>不把它写回 title</b>：那是"原本没标题"的历史事实，
     * 悄悄改成"有标题"会让日后分不清哪些是自动命名的。
     *
     * @param sessionIds 会话业务ID集合；为空时返回空表（不查库）
     * @param limit      单次限制（避免 IN 列表过长），见实现里的说明
     * @return 每个会话一条，{@code sessionId → content}；未命中的会话不在结果里
     */
    Map<String, String> firstUserMessages(List<String> sessionIds, int limit);

    /**
     * 物理删除若干会话下的全部消息。
     *
     * <p><b>为什么是物理删除</b>：{@code message} 表没有逻辑删除列
     * （全库只有 {@code conversation} 有）。会话被清理后，留下来的消息既读不到
     * （历史接口要求会话归属），又会让后台统计出现"没有会话的消息"这种不一致口径。
     *
     * <p>由 {@code ConversationCleanupService} 在事务里调用 —— 消息与会话的删除必须同生共死。
     *
     * @param sessionIds 会话业务ID集合；为空时什么都不做
     * @return 删除的消息条数
     */
    int deleteBySessionIds(List<String> sessionIds);
}
