package com.mewchat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mewchat.common.constant.ChatConstants;
import com.mewchat.common.util.HashUtils;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.mapper.LowConfidenceQuestionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 低置信度问题池服务实现。
 *
 * @author MewChat
 */
@Service
public class LowConfidenceQuestionServiceImpl
        extends ServiceImpl<LowConfidenceQuestionMapper, LowConfidenceQuestion>
        implements LowConfidenceQuestionService {

    private static final Logger log = LoggerFactory.getLogger(LowConfidenceQuestionServiceImpl.class);

    /** 优化状态：待优化 */
    private static final int OPTIMIZED_PENDING = 0;

    /** 优化状态：已优化（补完知识后由运营手动标记） */
    private static final int OPTIMIZED_DONE = 1;

    /**
     * 问题原文入库前的截断长度，与 {@code low_confidence_question.question} 的列宽一致。
     *
     * <p>取 {@link ChatConstants#MAX_MESSAGE_LENGTH} 而不是另写一个数字：两者必须相等 ——
     * 用户消息能到多长，问题池的列就得能放下。列窄于消息上限时，超长问题会在
     * 严格模式下报 1406 而整行插不进去，问题池里就永远看不到它，
     * 偏偏还没有任何报错（写入方的异常只记日志）。
     *
     * <p>这里仍然截断一次：即便有人把消息上限调得比列宽还大（或忘了执行
     * {@code sql/05_question_length.sql}），也只损失尾部文字，不会丢掉整条记录。
     */
    private static final int QUESTION_MAX_CHARS = ChatConstants.MAX_MESSAGE_LENGTH;

    @Override
    public void record(String question, BigDecimal confidence, String sessionId) {
        if (!StringUtils.hasText(question)) {
            return;
        }
        BigDecimal safeConfidence = confidence == null ? BigDecimal.ZERO : confidence;
        String hash = HashUtils.sha256Hex(HashUtils.normalizeQuestion(question));

        LowConfidenceQuestion existing = lambdaQuery()
                .eq(LowConfidenceQuestion::getQuestionHash, hash)
                .one();

        if (existing == null) {
            LowConfidenceQuestion created = LowConfidenceQuestion.builder()
                    .question(truncateQuestion(question))
                    .questionHash(hash)
                    .confidence(safeConfidence)
                    .hitCount(1)
                    .sessionId(sessionId)
                    .optimized(OPTIMIZED_PENDING)
                    .build();
            save(created);
            log.info("低置信度问题入池：hash={} confidence={} question={}", hash, safeConfidence, question);
            return;
        }

        // 置信度取更小的那个：要反映的是"这个问题最差能答成什么样"
        BigDecimal worstConfidence = existing.getConfidence() == null
                ? safeConfidence
                : existing.getConfidence().min(safeConfidence);

        // 说明：这里是"读-改-写"，并发下同一问题的 hitCount 可能少加一次。
        // 低置信度记录只在答不好时触发、频率很低，暂不引入复杂度；
        // 若要严格准确，可在 LowConfidenceQuestionMapper.xml 中用
        // INSERT ... ON DUPLICATE KEY UPDATE hit_count = hit_count + 1 做成原子操作。
        lambdaUpdate()
                .eq(LowConfidenceQuestion::getId, existing.getId())
                .set(LowConfidenceQuestion::getHitCount, existing.getHitCount() + 1)
                .set(LowConfidenceQuestion::getConfidence, worstConfidence)
                .set(LowConfidenceQuestion::getSessionId, sessionId)
                .update();

        log.info("低置信度问题重复出现：hash={} hitCount={}", hash, existing.getHitCount() + 1);
    }

    @Override
    public List<LowConfidenceQuestion> listPendingForClustering(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 命中次数相同时按主键升序：没有这个兜底排序，MySQL 返回顺序不保证稳定，
        // 而聚类是贪心的 —— 顺序一变，代表元与分簇结果就跟着变，日报会今天一个样明天一个样
        Page<LowConfidenceQuestion> page = batchPage(limit);
        return page(page, Wrappers.<LowConfidenceQuestion>lambdaQuery()
                .eq(LowConfidenceQuestion::getOptimized, OPTIMIZED_PENDING)
                .orderByDesc(LowConfidenceQuestion::getHitCount)
                .orderByAsc(LowConfidenceQuestion::getId)).getRecords();
    }

    @Override
    public int assignCluster(Collection<Long> questionIds, String clusterKey, int clusterSize) {
        if (CollectionUtils.isEmpty(questionIds)) {
            return 0;
        }
        boolean updated = lambdaUpdate()
                .in(LowConfidenceQuestion::getId, questionIds)
                .set(LowConfidenceQuestion::getClusterKey, clusterKey)
                .set(LowConfidenceQuestion::getClusterSize, clusterSize)
                .update();
        return updated ? questionIds.size() : 0;
    }

    @Override
    public List<LowConfidenceQuestion> listPendingForChecklist(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Page<LowConfidenceQuestion> page = batchPage(limit);
        return page(page, Wrappers.<LowConfidenceQuestion>lambdaQuery()
                .eq(LowConfidenceQuestion::getOptimized, OPTIMIZED_PENDING)
                .orderByDesc(LowConfidenceQuestion::getClusterSize)
                .orderByDesc(LowConfidenceQuestion::getHitCount)
                .orderByAsc(LowConfidenceQuestion::getId)).getRecords();
    }

    @Override
    public boolean markOptimized(Long questionId, Long knowledgeDocId) {
        if (questionId == null || knowledgeDocId == null) {
            return false;
        }
        // 只更新"待优化"的行：已优化/已忽略的问题不该被重复计数，
        // 同时这也让"重复点击"变成一个无害操作（返回 false，不改变任何数据）
        boolean updated = lambdaUpdate()
                .eq(LowConfidenceQuestion::getId, questionId)
                .eq(LowConfidenceQuestion::getOptimized, OPTIMIZED_PENDING)
                .set(LowConfidenceQuestion::getOptimized, OPTIMIZED_DONE)
                .set(LowConfidenceQuestion::getKnowledgeDocId, knowledgeDocId)
                .set(LowConfidenceQuestion::getOptimizeTime, LocalDateTime.now())
                .update();
        if (updated) {
            log.info("问题已标记为已优化：questionId={} knowledgeDocId={}", questionId, knowledgeDocId);
        }
        return updated;
    }

    /**
     * 构造一个"按调用方要求取数"的分页对象。
     *
     * <p><b>必须显式设置 maxLimit，否则请求条数会被静默改小。</b>
     * {@code MyBatisPlusConfig} 上挂了单页上限 {@code MAX_PAGE_SIZE}(100) 作为防拖库保护，
     * 而分页插件在 {@code Page} 自身没设 maxLimit 时一律按全局上限截断 ——
     * {@code Page.of(1, 500, false)} 实际只会取回 100 条，既不报错也不打日志。
     * 聚类配置写着 500（{@code mewchat.job.clustering.batch-size}）却只处理前 100 条，
     * 配上"按命中次数降序"的排序，每次跑的都是同一批，
     * 剩下的问题永远轮不到聚类、{@code cluster_key} 永远为空。
     *
     * <p>这些方法取数条数都由配置驱动、不是外部输入，因此这里如实按请求条数取，
     * 让配置说的和实际发生的一致；页大小防拖库仍由接口层的 {@code clampPageSize} 负责。
     *
     * @param limit 请求条数
     * @return 分页对象
     */
    private static Page<LowConfidenceQuestion> batchPage(int limit) {
        Page<LowConfidenceQuestion> page = Page.of(1, limit, false);
        page.setMaxLimit((long) limit);
        return page;
    }

    /**
     * 把问题原文截断到列宽以内。
     *
     * <p>注意 {@code question_hash} 仍按<b>原文</b>计算（去重靠它），
     * 截断只影响展示用的原文，不影响聚合与去重口径。
     *
     * @param question 用户问题原文
     * @return 截断后的问题原文
     */
    private static String truncateQuestion(String question) {
        if (question.length() <= QUESTION_MAX_CHARS) {
            return question;
        }
        log.warn("问题原文过长已截断入池：原长 {} 截断为 {}", question.length(), QUESTION_MAX_CHARS);
        return question.substring(0, QUESTION_MAX_CHARS);
    }
}
