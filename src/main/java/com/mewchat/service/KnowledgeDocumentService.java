package com.mewchat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;

import java.util.List;

/**
 * 知识库文档业务服务。
 *
 * @author MewChat
 */
public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    /**
     * 回写向量化结果。
     *
     * <p>切片与向量化是异步过程，这个状态字段是"用户刚上传的文档现在能不能被检索到"
     * 的唯一答案来源，因此每次处理完都必须回写，包括失败的情况。
     *
     * @param docId      文档ID
     * @param status     状态：0待处理 1处理中 2已入库 3失败
     * @param chunkCount 切片数量
     * @param error      失败原因，成功时传 null
     */
    void markEmbedResult(Long docId, int status, int chunkCount, String error);

    /**
     * 按向量化状态查询文档。
     *
     * <p>供补偿任务使用：扫描"待处理"或"失败"的文档重新入库，
     * 解决"上传时向量库正好不可用"这类需要事后重试的场景。
     *
     * @param status 状态
     * @param limit  条数上限
     * @return 文档列表
     */
    List<KnowledgeDocument> listByEmbedStatus(int status, int limit);

    /**
     * 分页查询文档（运营后台用）。
     *
     * <p><b>不返回正文</b>：单篇文档动辄几万字，列表接口带上它会让一次翻页
     * 返回几百 KB。后台列表要看的是"有没有入库、切了多少片"，
     * 正文只在检索时才有意义。
     *
     * @param keyword  标题关键字，为空表示不筛选
     * @param pageNo   页码，从 1 开始；非法值兜到 1
     * @param pageSize 每页条数；非法值兜到默认值，且有上限
     * @return 分页结果
     */
    Page<KnowledgeDocument> pageForAdmin(String keyword, int pageNo, int pageSize);
}
