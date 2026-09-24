package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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

/**
 * 会话表实体，对应 {@code conversation} 表。
 *
 * <p>一次"用户打开对话框到关闭"的过程即为一个会话。
 * {@code sessionId} 是对外暴露的业务标识，所有对话接口都以它为准；
 * 内部主键 {@code id} 仅供数据库层面使用。
 *
 * <p>{@code @TableName} 必须带 {@code autoResultMap = true}：否则带
 * {@link JacksonTypeHandler} 的字段只在写入时被转换，<b>查出来是一串未解析的 JSON 文本</b>，
 * 拿到 {@link #pendingClarification} 会是一个类型不符的对象。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "conversation", autoResultMap = true)
public class Conversation {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话业务ID（UUID），对外唯一标识，接口参数用它 */
    private String sessionId;

    /** 所属用户ID；为空表示未登录游客会话 */
    private Long userId;

    /** 会话标题，供会话列表展示 */
    private String title;

    /** 状态：1进行中 2已结束 3已转人工 */
    private Integer status;

    /** 会话开始时间 */
    private LocalDateTime startTime;

    /** 会话结束时间，未结束为空 */
    private LocalDateTime endTime;

    /** AI 生成的会话摘要，供长期记忆与列表预览使用 */
    private String summary;

    /**
     * 挂起的澄清追问，没有待澄清项时为 null。
     *
     * <p>数据库列类型为 JSON，由 {@link JacksonTypeHandler} 完成
     * {@link PendingClarification} ↔ JSON 字符串的转换。
     *
     * <p><b>{@code updateStrategy = ALWAYS} 不能省</b>：MyBatis-Plus 默认<b>忽略值为 null
     * 的字段</b>，而"清空挂起状态"正是把这一列置为 null —— 用默认策略会导致
     * 清空语句里根本没有这一列，挂起项永远不会被清掉，
     * 表现是用户早就答完了、之后随口回一个"1"又被续接回旧追问。
     */
    @TableField(value = "pending_clarification", typeHandler = JacksonTypeHandler.class,
            updateStrategy = FieldStrategy.ALWAYS)
    private PendingClarification pendingClarification;

    /**
     * 消息条数。
     *
     * <p>冗余字段：会话列表是最高频查询，不冗余就得对写入量最大的 message 表做聚合。
     * 写入消息时由 service 层同步维护。
     */
    private Integer messageCount;

    /** 最后一条消息时间（冗余），用于会话列表排序 */
    private LocalDateTime lastMessageTime;

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
