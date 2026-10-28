package com.mewchat.dao.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
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
 * 订单实体，客服"订单查询"工具的数据源（对应表 {@code orders}）。
 *
 * <p>表名用 {@code orders} 而不是 {@code order}：ORDER 是 MySQL 保留字。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("orders")
public class Order {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单号 */
    private String orderNo;

    /** 归属用户ID：候选订单按它过滤（列出别人的订单是泄露交易信息） */
    private Long ownerUserId;

    /** 订单状态：待发货 / 已发货 / 已完成 */
    private String status;

    /** 商品名，与商品表的 product_name 保持同名 */
    private String productName;

    /** 数量 */
    private Integer quantity;

    /** 实付金额 */
    private BigDecimal amount;

    /** 下单时间 */
    private LocalDateTime orderTime;

    /** 收货地址 */
    private String receiverAddress;

    /** 收件人（数据里本身就是脱敏值：客服场景不需要完整 PII） */
    private String receiverName;

    /** 收件人手机（脱敏值） */
    private String receiverPhone;

    /** 承运商，未发货时为 null */
    private String carrier;

    /** 运单号，未发货时为 null */
    private String trackingNo;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
