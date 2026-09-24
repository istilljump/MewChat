package com.mewchat.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话历史中的一条消息。
 *
 * <p>只暴露前端要渲染的字段：消息正文、角色、时间、处理方、置信度与引用来源。
 * <b>不透出内部字段</b>（错误原因、token 明细、模型名等）—— 那些是运维视角的信息，
 * 放在面向用户的接口里既无用处，又会泄露实现细节。
 *
 * @param id         消息ID。雪花ID 为 19 位，JacksonConfig 已全局序列化为字符串，
 *                   避免前端把它当数字解析时丢精度
 * @param role       角色：user / assistant
 * @param content    消息正文
 * @param createTime 创建时间
 * @param agentName  处理该问题的专家节点名（如 RagSpecialist），用户消息为 null
 * @param confidence 本轮置信度，用户消息为 null
 * @param citations  引用的知识片段，供前端展示"参考来源"；无引用时为空列表
 * @author MewChat
 */
public record ChatMessageView(

        Long id,

        String role,

        String content,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime createTime,

        String agentName,

        BigDecimal confidence,

        List<CitationView> citations) {

    /**
     * 引用来源。
     *
     * <p><b>不直接返回检索层的切片对象</b>：那种对象里有相似度、召回通道等调试字段，
     * 是"为什么召回它"的依据，不是"给用户看的出处"。接口只给标题与段落号，
     * 前端就能展示"依据《七天无理由退换货规则》第 3 段"。
     *
     * @param chunkId  切片ID
     * @param docTitle 来源文档标题
     * @param chunkNo  文档内的段落号
     * @param score    相关度，0~1
     */
    public record CitationView(String chunkId, String docTitle, Integer chunkNo, Double score) {
    }
}
