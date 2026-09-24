package com.mewchat.api.admin;

import com.mewchat.api.admin.dto.MarkOptimizedRequest;
import com.mewchat.api.admin.dto.OptimizationChecklistView;
import com.mewchat.api.admin.dto.StatsOverviewView;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import com.mewchat.service.KnowledgeDocumentService;
import com.mewchat.service.LowConfidenceQuestionService;
import com.mewchat.service.QuestionClusteringService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据统计与数据飞轮接口（运营后台）。
 *
 * <p>四个接口对应运营的四件事：
 * <ul>
 *     <li><b>总览</b>：系统用得怎么样、答得好不好、哪里在漏水</li>
 *     <li><b>待优化清单</b>：把问题池聚类后的结果按影响面排序，是"补知识"的输入</li>
 *     <li><b>立即重算聚类</b>：攒了一批新问题就不必等到第二天</li>
 *     <li><b>标记已优化</b>：补完知识后把问题从清单上销掉，<b>这一步是整个飞轮的收口</b></li>
 * </ul>
 *
 * <p>飞轮的闭环是：答不好 → 进问题池 → 聚类成主题 → 运营补文档 → 标记已优化 → 同一问题下次答得出。
 * 中间三步系统自动完成，"补文档"靠人，"标记已优化"则必须由接口写回 ——
 * 少了它，清单只增不减，下一个来看清单的人会把同一批问题重新处理一遍。
 *
 * @author MewChat
 */
@RestController
@RequestMapping("/api/admin/analytics")
public class AnalyticsAdminController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAdminController.class);

    private final AdminStatsAssembler statsAssembler;

    private final QuestionClusteringService clusteringService;

    private final LowConfidenceQuestionService questionService;

    private final KnowledgeDocumentService documentService;

    public AnalyticsAdminController(AdminStatsAssembler statsAssembler,
                                    QuestionClusteringService clusteringService,
                                    LowConfidenceQuestionService questionService,
                                    KnowledgeDocumentService documentService) {
        this.statsAssembler = statsAssembler;
        this.clusteringService = clusteringService;
        this.questionService = questionService;
        this.documentService = documentService;
    }

    /**
     * 数据总览。
     *
     * @return 总览视图
     */
    @GetMapping("/overview")
    public Result<StatsOverviewView> overview() {
        return Result.success(statsAssembler.overview());
    }

    /**
     * 待优化清单（按簇规模排序）。
     *
     * @return 清单视图
     */
    @GetMapping("/optimization-checklist")
    public Result<OptimizationChecklistView> checklist() {
        return Result.success(statsAssembler.checklist());
    }

    /**
     * 立即重算问题池聚类。
     *
     * <p>只是提前触发定时任务做的事，不做任何"增量"处理 ——
     * 聚类是全量重算的，多跑一次的结果与定时跑完全一致，因此可以放心重复点。
     *
     * @return 本次聚类结果摘要
     */
    @PostMapping("/clustering/recluster")
    public Result<ReclusterResult> recluster() {
        QuestionClusteringService.ClusterOutcome outcome = clusteringService.clusterPendingQuestions();

        log.info("后台手动重算聚类：参与 {} 条，得到 {} 个簇，更新 {} 行",
                outcome.pendingQuestions(), outcome.clusterCount(), outcome.updatedRows());

        return Result.success(new ReclusterResult(
                outcome.pendingQuestions(),
                outcome.clusterCount(),
                outcome.multiMemberClusters(),
                outcome.updatedRows()), "聚类已重算");
    }

    /**
     * 把某个问题标记为"已优化"，并关联到解答它的知识文档。
     *
     * <p>这是飞轮的收口动作：运营补完文档后调用它，该问题即从待优化清单上消失。
     * 不做这个写回，清单只增不减，等于每一轮都在重复处理同一批问题。
     *
     * <p>会先确认知识文档存在：关联一个不存在的ID既没有意义，
     * 又会让日后"这条知识解决了多少问题"的统计出现指向不了任何东西的记录。
     *
     * @param questionId 问题池记录ID
     * @param request    请求体，携带解答该问题的文档ID
     * @return 空响应
     */
    @PostMapping("/questions/{questionId}/optimize")
    public Result<Void> markOptimized(@PathVariable Long questionId,
                                      @Valid @RequestBody MarkOptimizedRequest request) {
        if (documentService.getById(request.knowledgeDocId()) == null) {
            return Result.error(ResultCode.NOT_FOUND, "知识文档不存在：" + request.knowledgeDocId());
        }
        boolean updated = questionService.markOptimized(questionId, request.knowledgeDocId());
        if (!updated) {
            // 记录不存在与"已经是已优化状态"在这里不做区分：对运营而言两者都是
            // "这次没改动任何东西"，而区分它们需要多查一次库、收益也仅是提示语更细
            log.info("问题未发生变化（不存在或已优化）：questionId={}", questionId);
            return Result.error(ResultCode.NOT_FOUND, "问题不存在或已是已优化状态：" + questionId);
        }
        return Result.success();
    }

    /**
     * 聚类结果摘要（对外）。
     *
     * <p>在这里定义而不是直接返回服务层的记录：服务的返回类型是内部实现的一部分，
     * 直接透出去就等于把内部结构变成了接口契约 —— 之后想在返回值里
     * 加一个内部字段，都会变成一次不兼容的接口变更。
     *
     * @param pendingQuestions    参与聚类的问题条数
     * @param clusterCount        得到的簇数量
     * @param multiMemberClusters 其中被真正归并（成员数 > 1）的簇数量
     * @param updatedRows         实际写回的行数
     */
    public record ReclusterResult(int pendingQuestions,
                                  int clusterCount,
                                  int multiMemberClusters,
                                  int updatedRows) {
    }
}
