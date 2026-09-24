package com.mewchat.agent;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 对话历史中的一轮发言。
 *
 * <p>为什么不直接用 {@code dao.mysql.entity.Message}：那是数据库实体的形状
 * （带主键、token 统计、逻辑删除标记等），编排层只关心"谁说了什么"。
 * 用独立的值对象承载，避免实体改动直接冲击提示词构造逻辑，
 * 也符合"数据库实体与传输对象不混用"的分层要求。
 *
 * @author MewChat
 */
@Getter
@ToString
@AllArgsConstructor
public class HistoryTurn {

    /** 角色：user / assistant / system，取值见 ChatConstants.ROLE_* */
    private final String role;

    /** 发言内容 */
    private final String content;

    /**
     * 是否为用户发言。
     *
     * @return true 表示这轮是用户说的
     */
    public boolean isUser() {
        return "user".equalsIgnoreCase(role);
    }
}
