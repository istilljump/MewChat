package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 物流实体，客服"物流查询"工具的数据源（对应表 {@code logistics}）。
 *
 * <p>{@code traces} 是 JSON 列，存倒序轨迹列表（第一条即最新轨迹，不单独存"最新"字段
 * —— 存两份迟早互相矛盾）。JSON 列必须配 {@code autoResultMap = true}，
 * 否则查回来是一串未解析的 JSON 文本（阶段 8 踩过的坑）。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "logistics", autoResultMap = true)
public class Logistics {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 运单号 */
    private String trackingNo;

    /** 承运商 */
    private String carrier;

    /** 物流状态：待揽收 / 运输中 / 已签收 */
    private String status;

    /** 预计送达时间，已签收时为 null */
    private LocalDateTime estimatedDeliveryTime;

    /**
     * 轨迹列表（倒序）。紧凑构造没有、直接在 getter 处规范为非 null：
     * 与迁移前 {@code LogisticsInfo} 的"可能为 null 的假设收敛到一处"同理。
     */
    @TableField(value = "traces", typeHandler = JacksonTypeHandler.class,
            updateStrategy = FieldStrategy.ALWAYS)
    @Builder.Default
    private List<LogisticsTrace> traces = new ArrayList<>();

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /**
     * 单条物流轨迹。
     */
    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogisticsTrace {

        /** 轨迹时间 */
        private LocalDateTime time;

        /** 轨迹描述 */
        private String description;
    }
}
