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

import java.util.ArrayList;
import java.util.List;

/**
 * 退款政策实体，客服"退款政策查询"工具的数据源（对应表 {@code refund_policy}）。
 *
 * <p>"通用"是兜底类目（matched=false 的语义在工具层）：类目检索词都匹配不上时按它答复。
 * 商品表的 {@code category} 必须能在此表查到 —— 跨表参照完整性由集成测试钉住。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "refund_policy", autoResultMap = true)
public class RefundPolicy {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 类目名 */
    private String category;

    /** 退货期限（天），0 = 不支持 */
    private Integer returnWindowDays;

    /** 换货期限（天），0 = 不支持 */
    private Integer exchangeWindowDays;

    /** 政策要点列表 */
    @TableField(value = "rules", typeHandler = JacksonTypeHandler.class,
            updateStrategy = FieldStrategy.ALWAYS)
    @Builder.Default
    private List<String> rules = new ArrayList<>();

    /** 补充说明 */
    private String note;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
