package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.dao.mysql.entity.KnowledgeChunk;
import com.mewchat.dao.mysql.mapper.KnowledgeChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 知识切片业务服务实现。
 *
 * @author MewChat
 */
@Service
public class KnowledgeChunkServiceImpl
        extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunk>
        implements KnowledgeChunkService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkServiceImpl.class);

    @Override
    public List<KnowledgeChunk> searchByKeyword(String booleanQuery, int limit) {
        if (booleanQuery == null || booleanQuery.isBlank() || limit <= 0) {
            return List.of();
        }
        return baseMapper.searchByKeyword(booleanQuery, limit);
    }

    @Override
    public long countByTerm(String term) {
        if (term == null || term.isBlank()) {
            return 0L;
        }
        return baseMapper.countByTerm(term);
    }

    @Override
    public long countAllChunks() {
        return baseMapper.countAllChunks();
    }

    @Override
    public double averageContentLength() {
        return baseMapper.averageContentLength();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceChunks(Long docId, List<KnowledgeChunk> chunks) {
        if (docId == null) {
            return;
        }
        // 物理删除而非逻辑删除：切片表没有 deleted 字段，且唯一键 (doc_id, chunk_no)
        // 不允许软删的行继续占位，否则该文档无法重新入库
        remove(Wrappers.<KnowledgeChunk>lambdaQuery().eq(KnowledgeChunk::getDocId, docId));

        if (CollectionUtils.isEmpty(chunks)) {
            log.info("文档 {} 未产生有效切片，已清空其历史切片", docId);
            return;
        }
        saveBatch(chunks);
        log.info("文档 {} 切片已写入，共 {} 条", docId, chunks.size());
    }

    @Override
    public void deleteByDocId(Long docId) {
        if (docId == null) {
            return;
        }
        remove(Wrappers.<KnowledgeChunk>lambdaQuery().eq(KnowledgeChunk::getDocId, docId));
    }
}
