package com.mewchat.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * "标记问题已优化"的请求体。
 *
 * <p>单独定义而不是复用实体：请求里只该出现运营能决定的两件事 ——
 * 哪条问题、用哪篇文档解决的。{@code optimized} 状态、优化时间这些由服务端自己写，
 * 让调用方传状态意味着"客户端可以决定服务端状态"，那是越权。
 *
 * @param knowledgeDocId 解答该问题的知识文档ID
 * @author MewChat
 */
public record MarkOptimizedRequest(
        @NotNull(message = "知识文档ID不能为空")
        @Positive(message = "知识文档ID必须为正数")
        Long knowledgeDocId) {
}
