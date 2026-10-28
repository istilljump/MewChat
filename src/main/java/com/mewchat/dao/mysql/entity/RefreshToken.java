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

import java.time.LocalDateTime;

/**
 * 刷新令牌实体（对应表 {@code refresh_token}）。
 *
 * <p>与访问令牌的分工：访问令牌无状态、不可吊销（换密钥全体失效）；
 * 刷新令牌落库、可吊销，是"强制下线""登出""续期"的执行点。
 * 表里只有令牌的 SHA-256 摘要 —— 拖库换不出可用凭据。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("refresh_token")
public class RefreshToken {

    /** 主键，雪花算法生成 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户ID */
    private Long userId;

    /** 令牌的 SHA-256 十六进制摘要（64 字符） */
    private String tokenHash;

    /** 过期时间，每次旋转重新计 */
    private LocalDateTime expireTime;

    /** 逻辑删除：1 = 已旋转或已登出 */
    @TableLogic
    private Integer deleted;
}
