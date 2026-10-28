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

/**
 * 商品实体，客服"商品查询"工具的数据源（对应表 {@code product}）。
 *
 * <p>公开目录、不挂归属（与订单刻意相反）；{@code category} 必须能在
 * {@code refund_policy} 表中查到（跨表参照完整性由集成测试钉住）。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("product")
public class Product {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 商品名，订单里的商品名与此同名 */
    private String productName;

    /** 类目 */
    private String category;

    /** 售价 */
    private BigDecimal price;

    /** 规格说明 */
    private String spec;

    /** 库存 */
    private Integer stock;

    /** 是否在售：候选列表只列在售商品 */
    private Boolean onSale;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
