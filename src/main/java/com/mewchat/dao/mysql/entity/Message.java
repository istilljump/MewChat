package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息表实体，对应 {@code message} 表。全库写入量最大的表。
 *
 * <p><b>追加型实体的两个刻意选择</b>：
 * <ul>
 *     <li>没有 {@code deleted}：消息是对话审计日志，逻辑删除会让"是否被篡改过"无法判断。
 *         用户删除会话时软删 {@code conversation}，消息留痕，查询时按会话状态过滤</li>
 *     <li>没有 {@code updateTime}：消息生成后不再修改</li>
 * </ul>
 *
 * <p>{@code @TableName} 必须带 {@code autoResultMap = true}，否则
 * {@link JacksonTypeHandler} 只在写入时生效、查询回来会是一个未解析的字符串。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "message", autoResultMap = true)
public class Message {

    /** 主键，雪花算法生成。同时用于同一会话内的消息排序（雪花ID单调递增） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话业务ID，对应 conversation.session_id */
    private String sessionId;

    /** 角色：user / assistant / system / tool，取值见 ChatConstants.ROLE_* */
    private String role;

    /** 消息正文 */
    private String content;

    /** 本轮生成耗时（毫秒） */
    private Integer costMs;

    /** 输入 token 数 */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** 总 token 数（冗余，便于直接聚合统计，省去每次相加） */
    private Integer totalTokens;

    /** 本轮使用的模型名，用于成本归因与效果对比 */
    private String modelName;

    /** 本轮由哪个子 Agent 处理（supervisor 路由结果） */
    private String agentName;

    /**
     * 本轮置信度，0.0000~1.0000。
     *
     * <p>低于阈值的问题会被写入低置信度问题池，是那套补漏机制的判据来源。
     */
    private BigDecimal confidence;

    /**
     * RAG 引用的知识片段。
     *
     * <p>数据库列类型为 JSON，由 {@link JacksonTypeHandler} 自动完成
     * {@code List<MessageRefDoc>} ↔ JSON 字符串的转换。
     */
    @TableField(value = "ref_docs", typeHandler = JacksonTypeHandler.class)
    private List<MessageRefDoc> refDocs;

    /** 状态：1成功 0失败 */
    private Integer status;

    /** 失败原因，仅 status=0 时有值 */
    private String errorMsg;

    /**
     * 用户对这条回答的反馈：1 有用 / 2 无用，null 表示未反馈。
     *
     * <p>取值见 {@code MessageService.FEEDBACK_UP / FEEDBACK_DOWN}。
     * <b>清空反馈要注意</b>：MyBatis-Plus 默认忽略值为 null 的字段，
     * 想让这一列变回 null 必须显式 {@code FieldStrategy.ALWAYS}（本项目在
     * {@code pending_clarification} 上踩过同一个坑）。当前业务只做"改主意"（1↔2），
     * 不需要清空，因此保持默认策略。
     */
    private Integer feedback;

    /** 最近一次反馈的时间，未反馈时为 null */
    private LocalDateTime feedbackTime;

    /** 创建时间，插入时自动填充。本表无更新时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
