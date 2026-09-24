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
 * 用户表实体，对应 {@code user} 表。
 *
 * <p>客户、客服、管理员共用本表，靠 {@code userType} 区分；
 * 工单的处理人（{@code Ticket#handlerId}）也指向本表主键。
 *
 * <p>未用 Lombok 的 {@code @Data}：实体应保持"身份相等"语义，
 * 按全部字段生成 equals/hashCode 会在集合操作中产生意外行为。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString(exclude = "password")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class User {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名，唯一 */
    private String username;

    /** 密码密文（BCrypt）。已从 toString 中排除，避免哈希值进日志 */
    private String password;

    /** 显示昵称 */
    private String nickname;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 头像 URL */
    private String avatar;

    /** 用户类型：1客户 2客服 3管理员 */
    private Integer userType;

    /** 状态：1启用 0禁用 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 创建时间，插入时由 MetaObjectHandler 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间，插入与更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;
}
