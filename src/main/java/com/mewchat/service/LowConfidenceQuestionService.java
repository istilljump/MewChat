package com.mewchat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 低置信度问题池服务。
 *
 * <p>Agent 答不好的问题在这里沉淀，形成"发现问题 → 补充知识 → 提升效果"的闭环。
 * 池子分两步产生价值：
 * <ol>
 *     <li><b>写入时聚合</b>（{@link #record}）：完全相同的问法累加到一行，按哈希去重</li>
 *     <li><b>每日聚类</b>（{@link #listPendingForClustering} + {@link #assignCluster}）：
 *         把"问法不同、问的是同一件事"的问题归成簇，产出运营能直接看的待优化清单</li>
 * </ol>
 *
 * @author MewChat
 */
public interface LowConfidenceQuestionService extends IService<LowConfidenceQuestion> {

    /**
     * 记录一次低置信度问题。
     *
     * <p>语义是"聚合"而非"追加"：同一问题重复出现时累加 {@code hitCount}
     * 并保留更低的置信度，而不是新增一行 —— 池子的价值在于看出
     * "哪个问题被问得最多、答得最差"，而不是当日志。
     *
     * @param question   用户问题原文
     * @param confidence 本轮置信度
     * @param sessionId  会话业务ID
     */
    void record(String question, BigDecimal confidence, String sessionId);

    /**
     * 取待优化的问题，供聚类使用。
     *
     * <p><b>按命中次数降序返回是接口约定，不是实现细节</b>：聚类是贪心算法，
     * 先处理的问题会成为它所在簇的代表元。让"被问得最多的那条"当代表，
     * 清单上的簇标题才最有代表性。命中次数相同时按主键升序，保证结果可复现。
     *
     * @param limit 单次处理条数上限
     * @return 待优化的问题列表
     */
    List<LowConfidenceQuestion> listPendingForClustering(int limit);

    /**
     * 把一组问题标记为同一个簇。
     *
     * <p>一次更新一整个簇，而不是逐行更新：池子动辄几百行，
     * 逐行写库意味着几百次往返，而聚类是每日全量重算的。
     *
     * @param questionIds 该簇成员的问题ID
     * @param clusterKey  簇键（取代表元的问题哈希）
     * @param clusterSize 簇规模（含代表元）
     * @return 实际更新的行数；入参为空时返回 0
     */
    int assignCluster(Collection<Long> questionIds, String clusterKey, int clusterSize);

    /**
     * 取待优化清单，按簇规模、命中次数降序。
     *
     * <p>与 {@link #listPendingForClustering} 的区别在排序：聚类要"谁被问得多先归谁"，
     * 清单要"哪个主题影响面最大先看哪个"。同一个池子，两种视角。
     *
     * @param limit 最大返回条数
     * @return 待优化的问题列表
     */
    List<LowConfidenceQuestion> listPendingForChecklist(int limit);

    /**
     * 把问题标记为"已优化"，并关联到解答它的知识文档。
     *
     * <p>这是数据飞轮的<b>收口动作</b>：没有它，运营补完文档之后问题池里那几行
     * 状态永远停在"待优化"，清单不会收敛，下一个来看清单的人还会再把同一批问题
     * 重新处理一遍。{@code optimized} 与 {@code knowledge_doc_id} 两个字段
     * 从建表起就留着，缺的只是写回它们的入口。
     *
     * <p>关联文档ID不是可有可无的备注：日后要回答"这条知识到底解决了多少问题"、
     * 或者要评估某篇文档的效果，只能靠这条关联。
     *
     * @param questionId     问题池记录ID
     * @param knowledgeDocId 解答该问题的知识文档ID
     * @return true 表示确实更新了一行（false 说明记录不存在或已是已优化状态）
     */
    boolean markOptimized(Long questionId, Long knowledgeDocId);
}
