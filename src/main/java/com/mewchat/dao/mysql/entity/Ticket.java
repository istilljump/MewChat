package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 人工工单表实体，对应 {@code ticket} 表。
 *
 * <p>Agent 连续无法解决、或用户主动要求转人工时创建。
 * {@code handlerId} 指向 {@code user} 表中 {@code userType} 为 2(客服) / 3(管理员) 的记录。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket")
public class Ticket {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联会话业务ID，对应 conversation.session_id；可为空 */
    private String sessionId;

    /** 提单用户ID，对应 user.id */
    private Long userId;

    /** 工单类型：refund退款 / logistics物流 / product商品 / other其他 */
    private String type;

    /** 问题描述 */
    private String description;

    /** 状态：0待处理 1处理中 2已解决 3已关闭 */
    private Integer status;

    /** 处理人ID，对应 user.id（userType=2 或 3） */
    private Long handlerId;

    /** 处理完成时间 */
    private LocalDateTime finishTime;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
