package com.mewchat.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 知识库文档视图（后台）。
 *
 * <p><b>不含正文</b>：文档正文明细动辄几万字，列表里带上它会让一个列表接口返回几百 KB；
 * 需要看正文时应另开详情接口（当前未提供，运营主要通过检索效果来评估文档质量）。
 *
 * @param id              文档ID
 * @param title           标题
 * @param category        知识分类
 * @param embedStatus     向量化状态：0待处理 1处理中 2已入库 3失败
 * @param embedStatusLabel 状态中文说明，供后台直接展示
 * @param chunkCount      切片数量
 * @param embedError      失败原因，仅失败时有值
 * @param createTime      创建时间
 * @param updateTime      更新时间
 * @author MewChat
 */
public record KnowledgeDocumentView(

        Long id,

        String title,

        String category,

        Integer embedStatus,

        String embedStatusLabel,

        Integer chunkCount,

        String embedError,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime createTime,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime updateTime) {

    /**
     * 把向量化状态转成中文说明。
     *
     * <p>前端各自映射一套状态文案的话，两处迟早会不一致；
     * 状态的含义属于服务端契约，就由服务端给出。
     *
     * @param embedStatus 状态值
     * @return 中文说明
     */
    public static String statusLabel(Integer embedStatus) {
        if (embedStatus == null) {
            return "未知";
        }
        return switch (embedStatus) {
            case 0 -> "待处理";
            case 1 -> "处理中";
            case 2 -> "已入库";
            case 3 -> "入库失败";
            default -> "未知(" + embedStatus + ")";
        };
    }
}
