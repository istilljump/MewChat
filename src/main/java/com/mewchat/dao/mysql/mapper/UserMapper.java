package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.User;

/**
 * 用户表 Mapper。
 *
 * <p>单表 CRUD 全部由 {@link BaseMapper} 提供，本接口不定义任何方法。
 * 后续若出现复杂查询（如多表关联、聚合统计），<b>SQL 一律写在
 * {@code src/main/resources/mapper/UserMapper.xml}</b> 中，不用注解拼 SQL，
 * 便于统一审阅与调优。
 *
 * <p>无需 {@code @Mapper} 注解：由 {@code MyBatisPlusConfig} 上的
 * {@code @MapperScan("com.mewchat.dao")} 统一扫描注册。
 *
 * @author MewChat
 */
public interface UserMapper extends BaseMapper<User> {
}
