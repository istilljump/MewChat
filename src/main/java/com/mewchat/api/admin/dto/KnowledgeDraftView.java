package com.mewchat.api.admin.dto;

import java.util.List;

/**
 * AI 起草的知识文档草稿（运营后台）。
 *
 * <p>草稿<b>只读不写</b>：模型起草的内容可能有幻觉，是否采纳、怎么改
 * 由运营决定，入库走既有的文档录入接口。
 *
 * @param title      建议标题
 * @param content    建议正文（Markdown）
 * @param references 起草时参考的知识片段的文档标题（空 = 无片段可参考，
 *                   正文中应已标注"待人工确认"）
 * @author MewChat
 */
public record KnowledgeDraftView(
        String title,
        String content,
        List<String> references) {
}
