package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.Message;

/**
 * 消息表 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；需手写 SQL 时放
 * {@code src/main/resources/mapper/MessageMapper.xml}。
 *
 * <p>本表是全库写入量最大的表，后续若出现慢查询（如按时间范围统计 token 消耗），
 * 建议在 XML 中用显式列名而非 {@code SELECT *}。
 *
 * @author MewChat
 */
public interface MessageMapper extends BaseMapper<Message> {
}
