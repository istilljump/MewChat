package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 低置信度问题池实体，对应 {@code low_confidence_question} 表。
 *
 * <p>Agent 答不好的问题在此沉淀，用于知识库补漏 —— 这是本项目的"数据飞轮"。
 * 同一问题重复出现时累加 {@code hitCount} 而不是新增行，所以 {@code questionHash}
 * 上有唯一键做去重聚合。
 *
 * <p><b>本表刻意没有 {@code deleted} 字段</b>：{@code questionHash} 是唯一键，
 * 若用逻辑删除，被软删的行仍占着唯一键，同一个问题就再也插不进来了。
 * 因此"忽略"这个语义被并进 {@code optimized}（取值为 2）。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("low_confidence_question")
public class LowConfidenceQuestion {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户问题原文，取首次出现的写法 */
    private String question;

    /**
     * 问题归一化（去空白、去标点、转小写）后的 SHA-256 十六进制串。
     *
     * <p>唯一键，用于把"同一个问题的不同问法"聚合到一行。
     */
    private String questionHash;

    /**
     * 历史<b>最低</b>的一次置信度，0.0000~1.0000。
     *
     * <p>取最低而非最高：我们要捞的是"Agent 一直答不好"的问题，
     * 用最低值更能反映问题严重程度。
     */
    private BigDecimal confidence;

    /** 累计出现次数，用于排优化优先级 */
    private Integer hitCount;

    /** 最近一次出现的会话业务ID */
    private String sessionId;

    /** 优化状态：0待优化 1已优化 2已忽略（本表无逻辑删除，忽略语义由此承载） */
    private Integer optimized;

    /** 优化后生成的知识文档ID，对应 knowledge_document.id，形成闭环 */
    private Long knowledgeDocId;

    /** 优化完成时间 */
    private LocalDateTime optimizeTime;

    /**
     * 聚类后所属簇的键。
     *
     * <p>取该簇<b>代表元</b>（被问得最多的那条问题）的 {@code questionHash}，
     * 而不是簇的序号：序号每次重算都会变，运营无法据此判断"我上次看的是不是同一批"。
     * 为 null 表示尚未参与过聚类。
     */
    private String clusterKey;

    /**
     * 所在簇的规模（含代表元），未聚类时为 0。
     *
     * <p>冗余存下来的理由：清单按规模排序，若不冗余就得每次把整池捞出来在内存分组 ——
     * 池子小的时候无所谓，池子一大就成了"打开清单页就卡"。
     */
    private Integer clusterSize;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
