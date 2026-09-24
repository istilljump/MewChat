package com.mewchat.dao.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;

/**
 * 知识库文档表 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；需手写 SQL 时放
 * {@code src/main/resources/mapper/KnowledgeDocumentMapper.xml}。
 *
 * @author MewChat
 */
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
