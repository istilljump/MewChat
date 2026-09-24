package com.mewchat.rag.document;

import com.mewchat.dao.milvus.MilvusVectorDao;
import com.mewchat.dao.mysql.entity.KnowledgeChunk;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.service.KnowledgeChunkService;
import com.mewchat.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识文档入库服务：分片 → 落库 → 向量化。
 *
 * <p><b>为什么切片要同时写 MySQL 和 Milvus</b>：MySQL 侧支撑关键词召回与溯源，
 * Milvus 侧支撑语义召回，两者用 (docId, chunkNo) 对齐。写 MySQL 与写 Milvus
 * 刻意<b>不放在同一个事务里</b>：向量库写入失败时，MySQL 侧的关键词索引仍然有效，
 * 知识依然可被检索到（只是少了语义通道），这比整批回滚、文档完全不可用要好。
 * 失败状态会如实回写到 {@code embed_status}，便于事后重新入库补齐向量。
 *
 * <p><b>重新入库幂等</b>：每次都先按 docId 删掉旧的 MySQL 切片与 Milvus 向量再写入。
 * 因为段落号在重新分片后会整体变化，逐条比对更新毫无意义，
 * 整体替换才是正确做法。
 *
 * @author MewChat
 */
@Component
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    /** 向量化状态：待处理 */
    private static final int STATUS_PENDING = 0;

    /** 向量化状态：处理中 */
    private static final int STATUS_PROCESSING = 1;

    /** 向量化状态：已入库（关键词与向量均写入成功） */
    private static final int STATUS_INDEXED = 2;

    /** 向量化状态：失败 */
    private static final int STATUS_FAILED = 3;

    /** 未指定分类时的默认值，与建表脚本里的默认值保持一致 */
    private static final String DEFAULT_CATEGORY = "default";

    private final KnowledgeDocumentService documentService;

    private final KnowledgeChunkService chunkService;

    private final DocumentSplitter splitter;

    private final ObjectProvider<MilvusVectorDao> vectorDaoProvider;

    public DocumentIngestService(KnowledgeDocumentService documentService,
                                 KnowledgeChunkService chunkService,
                                 DocumentSplitter splitter,
                                 ObjectProvider<MilvusVectorDao> vectorDaoProvider) {
        this.documentService = documentService;
        this.chunkService = chunkService;
        this.splitter = splitter;
        this.vectorDaoProvider = vectorDaoProvider;
    }

    /**
     * 入库一篇新文档。
     *
     * @param request 入库请求
     * @return 文档ID
     */
    public Long ingest(DocumentIngestRequest request) {
        KnowledgeDocument document = KnowledgeDocument.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .category(StringUtils.hasText(request.getCategory())
                        ? request.getCategory() : DEFAULT_CATEGORY)
                .fileName(request.getFileName())
                .fileType(request.getFileType())
                .fileSize(request.getFileSize())
                .filePath(request.getFilePath())
                .chunkCount(0)
                .embedStatus(STATUS_PENDING)
                .build();

        documentService.save(document);
        index(document);
        return document.getId();
    }

    /**
     * 重新入库已有文档：按当前分片策略重新切片并重建向量。
     *
     * <p>用于"改了分片参数"或"上次向量化失败"之后的修复。
     *
     * @param docId 文档ID
     * @return true 表示关键词与向量都写入成功
     */
    public boolean reindex(Long docId) {
        KnowledgeDocument document = documentService.getById(docId);
        if (document == null) {
            log.warn("文档 {} 不存在或已删除，跳过重新入库", docId);
            return false;
        }
        return index(document);
    }

    /**
     * 删除文档：清理 MySQL 切片、Milvus 向量与文档记录。
     *
     * <p>先向量后文档：万一中途失败，留下的是"文档还在但向量少了"，
     * 可通过重新入库修复；反过来则会留下"文档没了但向量还在"的孤儿向量，
     * 检索时会返回一条查不到标题的结果。
     *
     * @param docId 文档ID
     */
    public void delete(Long docId) {
        if (docId == null) {
            return;
        }
        chunkService.deleteByDocId(docId);

        MilvusVectorDao vectorDao = vectorDaoProvider.getIfAvailable();
        if (vectorDao != null) {
            try {
                vectorDao.deleteByDocId(docId);
            } catch (Exception e) {
                log.error("删除文档 {} 的向量失败，可能残留孤儿向量：{}", docId, e.getMessage());
            }
        } else {
            log.debug("Milvus 未启用，跳过文档 {} 的向量清理", docId);
        }

        boolean removed = documentService.removeById(docId);
        log.info("文档 {} 删除{}", docId, removed ? "完成" : "失败（记录可能已不存在）");
    }

    /**
     * 执行切片与入库，并回写状态。
     *
     * @param document 文档
     * @return true 表示关键词与向量都写入成功
     */
    private boolean index(KnowledgeDocument document) {
        Long docId = document.getId();
        documentService.markEmbedResult(docId, STATUS_PROCESSING, 0, null);

        List<KnowledgeChunk> chunks;
        try {
            List<DocumentSplitter.Piece> pieces = splitter.split(document.getContent());
            chunks = toEntities(docId, pieces);
            // 先删后插，保证重新入库时不会残留上一次的切片
            chunkService.replaceChunks(docId, chunks);
        } catch (Exception e) {
            log.error("文档 {} 切片入库失败", docId, e);
            documentService.markEmbedResult(docId, STATUS_FAILED, 0, "切片失败：" + e.getMessage());
            return false;
        }

        if (chunks.isEmpty()) {
            documentService.markEmbedResult(docId, STATUS_FAILED, 0, "文档内容为空或无法切出有效片段");
            return false;
        }

        MilvusVectorDao vectorDao = vectorDaoProvider.getIfAvailable();
        if (vectorDao == null) {
            // 向量库未启用不是错误：关键词检索依然可用，只是少了语义通道。
            // 状态仍标记为失败并说明原因，避免"看起来已入库、实际检索不到语义结果"
            documentService.markEmbedResult(docId, STATUS_FAILED, chunks.size(),
                    "向量库未启用，仅完成关键词索引；启用 Milvus 后重新入库可补齐向量");
            log.warn("文档 {} 关键词索引完成（{} 片），但向量库未启用，语义检索不可用", docId, chunks.size());
            return false;
        }

        try {
            vectorDao.deleteByDocId(docId);
            List<String> texts = chunks.stream().map(KnowledgeChunk::getContent).toList();
            vectorDao.addChunks(docId, document.getTitle(), document.getCategory(), texts);
            documentService.markEmbedResult(docId, STATUS_INDEXED, chunks.size(), null);
            log.info("文档 {} 入库完成：{} 片，关键词与向量均已写入", docId, chunks.size());
            return true;
        } catch (Exception e) {
            // 向量写入失败但关键词索引已生效，知识仍可被检索到，如实记录状态供事后补齐
            log.error("文档 {} 向量写入失败，关键词索引仍可用", docId, e);
            documentService.markEmbedResult(docId, STATUS_FAILED, chunks.size(),
                    "关键词索引已写入，向量写入失败：" + e.getMessage());
            return false;
        }
    }

    /**
     * 把分片结果转换为切片实体。
     *
     * @param docId  文档ID
     * @param pieces 分片结果
     * @return 切片实体列表
     */
    private List<KnowledgeChunk> toEntities(Long docId, List<DocumentSplitter.Piece> pieces) {
        List<KnowledgeChunk> chunks = new ArrayList<>(pieces.size());
        int chunkNo = 1;
        for (DocumentSplitter.Piece piece : pieces) {
            chunks.add(KnowledgeChunk.builder()
                    .docId(docId)
                    .chunkNo(chunkNo++)
                    .content(piece.text())
                    .charStart(piece.start())
                    .charEnd(piece.end())
                    // content_length 供 BM25 做长度归一化，插入时算好避免每次查询现算
                    .contentLength(piece.text().length())
                    .build());
        }
        return chunks;
    }
}
