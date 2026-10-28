package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.RefreshToken;

/**
 * 刷新令牌表数据访问（对应表 {@code refresh_token}）。
 *
 * <p>旋转的"条件失效"（只有未删除的行才能被置 1）在 service 层用
 * 带条件的 update 表达，本 Mapper 不加自定义方法。
 *
 * @author MewChat
 */
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
}
