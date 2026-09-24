package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;

/**
 * 低置信度问题池 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；需手写 SQL 时放
 * {@code src/main/resources/mapper/LowConfidenceQuestionMapper.xml}。
 *
 * <p>注意：本表实体没有逻辑删除字段，{@code delete*} 系列方法执行的是<b>物理删除</b>。
 * 常规业务应通过更新 {@code optimized} 字段来表达"不再关注"，而不是删行。
 *
 * @author MewChat
 */
public interface LowConfidenceQuestionMapper extends BaseMapper<LowConfidenceQuestion> {
}
