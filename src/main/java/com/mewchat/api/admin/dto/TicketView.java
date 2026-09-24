package com.mewchat.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 工单视图（后台）。
 *
 * @param id          工单ID
 * @param sessionId   关联会话业务ID，客服可据此查看完整对话记录
 * @param userId      提单用户ID
 * @param type        工单类型：refund / logistics / product / other
 * @param description 问题描述
 * @param status      状态：0待处理 1处理中 2已解决 3已关闭
 * @param statusLabel 状态中文说明
 * @param handlerId   处理人ID，未指派时为 null
 * @param finishTime  处理完成时间
 * @param createTime  创建时间
 * @author MewChat
 */
public record TicketView(

        Long id,

        String sessionId,

        Long userId,

        String type,

        String description,

        Integer status,

        String statusLabel,

        Long handlerId,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime finishTime,

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        LocalDateTime createTime) {

    /** 工单状态：待处理 */
    public static final int STATUS_PENDING = 0;

    /** 工单状态：处理中 */
    public static final int STATUS_PROCESSING = 1;

    /** 工单状态：已解决 */
    public static final int STATUS_RESOLVED = 2;

    /** 工单状态：已关闭 */
    public static final int STATUS_CLOSED = 3;

    /**
     * 把工单状态转成中文说明。
     *
     * @param status 状态值
     * @return 中文说明
     */
    public static String statusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_PENDING -> "待处理";
            case STATUS_PROCESSING -> "处理中";
            case STATUS_RESOLVED -> "已解决";
            case STATUS_CLOSED -> "已关闭";
            default -> "未知(" + status + ")";
        };
    }
}
