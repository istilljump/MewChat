package com.mewchat.api.admin;

import com.mewchat.api.admin.dto.CreateDocumentRequest;
import com.mewchat.api.admin.dto.KnowledgeDocumentView;
import com.mewchat.api.admin.dto.PageView;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.rag.document.DocumentIngestRequest;
import com.mewchat.rag.document.DocumentIngestService;
import com.mewchat.service.KnowledgeDocumentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理接口（运营后台）。
 *
 * <p>四个动作覆盖知识库的完整生命周期：<b>录入 → 查看 → 重建索引 → 删除</b>。
 * 全部复用已有能力（{@code DocumentIngestService} 在阶段 5 就已实现分片、
 * 关键词索引与向量入库），本类只做参数校验与响应封装。
 *
 * <p><b>"重建索引"不是可有可无的按钮</b>：向量化依赖外部服务，
 * 上传时正好不可用就会入库失败（文档状态为"入库失败"）。
 * 补偿任务之外，运营需要能手动重试单个文档，而不是等下一轮扫描。
 *
 * <p><b>删除是删干净</b>：切片同时存在 MySQL 与 Milvus 两侧，
 * 只删一边会留下"文档没了但还能被检索到"的幽灵结果。该逻辑在
 * {@code DocumentIngestService.delete} 内统一处理。
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeAdminController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAdminController.class);

    /** 向量化状态：已入库（与 sql/01_schema.sql 的取值约定一致） */
    private static final int EMBED_STATUS_INDEXED = 2;

    private final DocumentIngestService ingestService;

    private final KnowledgeDocumentService documentService;

    public KnowledgeAdminController(DocumentIngestService ingestService,
                                    KnowledgeDocumentService documentService) {
        this.ingestService = ingestService;
        this.documentService = documentService;
    }

    /**
     * 录入一篇知识文档并入库。
     *
     * @param request 文档内容
     * @return 文档ID
     */
    @PostMapping("/documents")
    public Result<Long> create(@Valid @RequestBody CreateDocumentRequest request) {
        Long docId = ingestService.ingest(DocumentIngestRequest.builder()
                .title(request.title())
                .content(request.content())
                .category(request.category())
                .build());

        log.info("后台录入知识文档：docId={} title={} 正文长度={}",
                docId, request.title(), request.content().length());
        return Result.success(docId, describeIngestOutcome(docId));
    }

    /**
     * 组一句如实的入库结果说明。
     *
     * <p><b>"录入成功"与"向量化成功"是两件事。</b> 切片先写 MySQL（关键词索引），
     * 再写 Milvus（语义索引）；向量库未启用或写入失败时，文档依然存在、
     * 关键词也照旧能检索到，只是少了语义召回。此时若只回一句"文档已入库"，
     * 运营会以为知识已经能被语义检索到，直到用户换个措辞问不出来才发现不对。
     * 同一控制器里的"重建索引"会如实报错，这里也必须说清楚。
     *
     * <p>刻意不改成返回错误：文档确实建成且可用，判成失败会让运营重复录入。
     *
     * @param docId 文档ID
     * @return 面向运营的结果说明
     */
    private String describeIngestOutcome(Long docId) {
        KnowledgeDocument document = documentService.getById(docId);
        if (document == null || document.getEmbedStatus() == null
                || document.getEmbedStatus() == EMBED_STATUS_INDEXED) {
            return "文档已入库";
        }
        return "文档已录入（关键词检索可用），但向量化未完成："
                + KnowledgeDocumentView.statusLabel(document.getEmbedStatus())
                + (StringUtils.hasText(document.getEmbedError())
                        ? "（" + document.getEmbedError() + "）" : "");
    }

    /**
     * 分页查询文档。
     *
     * @param page    页码
     * @param size    每页条数
     * @param keyword 标题关键字，可为空
     * @return 分页结果
     */
    @GetMapping("/documents")
    public Result<PageView<KnowledgeDocumentView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return Result.success(PageView.of(
                documentService.pageForAdmin(keyword, page, size), this::toView));
    }

    /**
     * 重建单个文档的索引（重新分片并重新向量化）。
     *
     * @param docId 文档ID
     * @return 空响应
     */
    @PostMapping("/documents/{docId}/reindex")
    public Result<Void> reindex(@PathVariable Long docId) {
        // 先确认文档存在，才能把"文档不存在"与"重建失败"区分开报给运营 ——
        // 这两种情况该做的事完全不同（前者是ID写错，后者要看服务端日志）
        if (documentService.getById(docId) == null) {
            return Result.error(ResultCode.NOT_FOUND, "文档不存在：" + docId);
        }
        boolean succeeded = ingestService.reindex(docId);
        log.info("后台重建文档索引：docId={} 成功={}", docId, succeeded);
        if (!succeeded) {
            return Result.error(ResultCode.SYSTEM_ERROR, "重建索引失败，请查看服务端日志");
        }
        return Result.success();
    }

    /**
     * 删除文档及其全部切片。
     *
     * @param docId 文档ID
     * @return 空响应
     */
    @DeleteMapping("/documents/{docId}")
    public Result<Void> delete(@PathVariable Long docId) {
        ingestService.delete(docId);
        log.info("后台删除知识文档：docId={}", docId);
        return Result.success();
    }

    /**
     * 把文档实体映射成对外视图。
     *
     * @param document 文档实体
     * @return 视图
     */
    private KnowledgeDocumentView toView(KnowledgeDocument document) {
        return new KnowledgeDocumentView(
                document.getId(),
                document.getTitle(),
                document.getCategory(),
                document.getEmbedStatus(),
                KnowledgeDocumentView.statusLabel(document.getEmbedStatus()),
                document.getChunkCount(),
                document.getEmbedError(),
                document.getCreateTime(),
                document.getUpdateTime());
    }
}
