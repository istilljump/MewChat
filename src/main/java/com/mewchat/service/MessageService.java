package com.mewchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mewchat.dao.mysql.entity.Message;

import java.util.List;

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
}
