package com.mewchat.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话消息视图（后台）。
 *
 * <p>比面向用户的 {@code ChatMessageView} 多出 token、耗时、失败原因等字段：
 * 客服接手一个工单时，需要判断"这轮为什么没答上" ——
 * 是模型超时（耗时极高、状态失败），还是知识库里确实没有（置信度为 0）。
 * 这些信息对用户没有意义，对客服却是决定下一步的依据。
 *
 * @param id          消息ID
 * @param role        角色：user / assistant
 * @param content     消息正文
 * @param agentName   处理该问题的专家节点名
 * @param confidence  本轮置信度
 * @param totalTokens 总 token 数
 * @param costMs      本轮耗时（毫秒）
 * @param status      状态：1成功 0失败
 * @param errorMsg    失败原因，仅失败时有值
 * @param citations   引用的知识片段
 * @param createTime  创建时间
 * @author MewChat
 */
public record AdminMessageView(

        Long id,

        String role,

        String content,

        String agentName,

        BigDecimal confidence,

        Integer totalTokens,

        Integer costMs,

        Integer status,

        String errorMsg,

        List<Citation> citations,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime createTime) {

    /**
     * 引用来源。
     *
     * @param chunkId  切片ID
     * @param docTitle 来源文档标题
     * @param chunkNo  文档内段落号
     */
    public record Citation(String chunkId, String docTitle, Integer chunkNo) {
    }
}
