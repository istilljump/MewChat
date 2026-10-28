package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.Order;

/**
 * 订单表数据访问（对应表 {@code orders}）。
 *
 * <p>单表 CRUD 走 {@link BaseMapper} 泛型方法，无手写 SQL；
 * 查询条件由 {@code service} 层组装（见 {@code BusinessDataServiceImpl}）。
 *
 * @author MewChat
 */
public interface OrderMapper extends BaseMapper<Order> {
}
