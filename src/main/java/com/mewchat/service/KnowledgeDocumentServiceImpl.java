package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.dao.mysql.mapper.KnowledgeDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识库文档业务服务实现。
 *
 * @author MewChat
 */
@Service
public class KnowledgeDocumentServiceImpl
        extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument>
        implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentServiceImpl.class);

    /** 后台分页的默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 后台分页的每页条数上限，防止 {@code size=100000} 把整张表读进内存 */
    private static final int MAX_PAGE_SIZE = 200;

    @Override
    public Page<KnowledgeDocument> pageForAdmin(String keyword, int pageNo, int pageSize) {
        return page(Page.of(Math.max(1, pageNo), clampPageSize(pageSize)),
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        // 按标题模糊匹配：运营找文档时记得住标题，记不住ID
                        .like(StringUtils.hasText(keyword), KnowledgeDocument::getTitle, keyword)
                        // 新入库的排前面：刚上传的文档最需要盯着看它入库成不成功
                        .orderByDesc(KnowledgeDocument::getCreateTime));
    }

    /**
     * 收敛每页条数。
     *
     * @param pageSize 请求的每页条数
     * @return 合法条数
     */
    private static int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    @Override
    public void markEmbedResult(Long docId, int status, int chunkCount, String error) {
        if (docId == null) {
            return;
        }
        // 失败时截断错误信息：embed_error 列宽 500，超长会被数据库拒绝，
        // 结果连"失败状态"都写不进去，问题反而更难查
        String safeError = StringUtils.hasText(error)
                ? (error.length() > 500 ? error.substring(0, 500) : error)
                : null;

        lambdaUpdate()
                .eq(KnowledgeDocument::getId, docId)
                .set(KnowledgeDocument::getEmbedStatus, status)
                .set(KnowledgeDocument::getChunkCount, chunkCount)
                .set(KnowledgeDocument::getEmbedError, safeError)
                .update();
        log.debug("文档 {} 向量化状态更新：status={} chunks={}", docId, status, chunkCount);
    }

    @Override
    public List<KnowledgeDocument> listByEmbedStatus(int status, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 显式给出 maxLimit：否则分页插件会套用全局单页上限(100)，
        // 请求条数大于它时会被静默改小（见 MyBatisPlusConfig 的说明）
        Page<KnowledgeDocument> page = Page.of(1, limit, false);
        page.setMaxLimit((long) limit);
        return page(page, Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getEmbedStatus, status)
                .orderByAsc(KnowledgeDocument::getId))
                .getRecords();
    }
}
