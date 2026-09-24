package com.mewchat.common.util;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 问题聚类：把"问的其实是同一件事"的问题归成一组。
 *
 * <p><b>为什么需要聚类</b>：问题池已经按"完全相同的问法"做过一次聚合（写入时按
 * 归一化后的哈希去重）。但运营真正要看的是"最近有 20 个人都在问赠品什么时候发货"——
 * 这 20 条问法各不相同，哈希聚合把它们放在 20 行里，看清单的人得自己肉眼归并。
 * 聚类就是把这一步自动化，产出一份"按主题分组、按规模排序"的待优化清单。
 *
 * <p><b>用的是字面相似度，不是语义相似度</b>：本算法对归一化后的文本切 2-gram，
 * 用 Jaccard 系数衡量相似度。它的局限必须说清楚：
 * <ul>
 *     <li>能把"赠品什么时候发货"和"赠品发货时间"归到一起（字面重合度高）</li>
 *     <li>归不动"赠品何时寄出"和"赠品什么时候发货"（字面几乎不重合）</li>
 * </ul>
 * 也就是说它是一份"能自动归并一部分、剩下的仍需人工看"的清单。
 * 真正该做的是用 embedding 算语义相似度（项目里已经有 embedding 模型与 Milvus），
 * 但那要等向量通道就绪 —— <b>本类的 {@link #cluster} 是可以整体替换的算法入口</b>，
 * 将来换成基于向量的实现时，调用方（定时任务与后台接口）不用改。
 *
 * <p><b>不做词干化、不做同义词表</b>：中文场景下这两件事要么收益有限、
 * 要么需要额外维护一张词表，而当前阶段的目标只是"少让人肉眼归并"。
 *
 * <p><b>结果是确定的</b>：同样输入必然得到同样输出 —— 顺序敏感的贪心算法配合
 * "调用方先按命中次数降序排好"的约定，让代表元选择稳定，日报不会今天一个样明天一个样。
 *
 * @author MewChat
 */
public final class QuestionClusterer {

    /** 阈值下限：低于它几乎什么都归不到一起，等于只做精确去重 */
    private static final double MIN_THRESHOLD = 0.1;

    /** 阈值上限：太高会把"问法接近但其实是两件事"的问题也分开，调到头就没有聚类效果了 */
    private static final double MAX_THRESHOLD = 0.95;

    /** 2-gram 的窗口大小，与检索侧的 ngram 索引保持一致 */
    private static final int NGRAM_SIZE = 2;

    private QuestionClusterer() {
    }

    /**
     * 对问题做聚类。
     *
     * <p><b>调用方必须先按优先级排好序</b>（通常是命中次数降序）：算法是贪心的，
     * 先来的问题成为它所在簇的代表元。让"被问得最多的那条"当代表，
     * 运营看到的簇标题才是最有代表性的那句问法。
     *
     * @param questions 问题原文，已按优先级排序
     * @param threshold 相似度阈值，落在 [0.1, 0.95] 之外会被裁剪
     * @return 簇列表，按代表元在原列表中的顺序排列；空输入返回空列表
     */
    public static List<Cluster> cluster(List<String> questions, double threshold) {
        if (CollectionUtils.isEmpty(questions)) {
            return List.of();
        }
        double effective = Math.min(MAX_THRESHOLD, Math.max(MIN_THRESHOLD, threshold));

        List<Set<String>> representativeTokens = new ArrayList<>();
        List<List<Integer>> memberGroups = new ArrayList<>();

        for (int index = 0; index < questions.size(); index++) {
            Set<String> tokens = tokenize(questions.get(index));
            if (tokens.isEmpty()) {
                // 归一化后什么都没有（纯标点、空白）：不参与聚类，
                // 否则它们会凭"空集合与空集合完全相似"聚成一大簇
                continue;
            }

            int matched = -1;
            for (int group = 0; group < representativeTokens.size(); group++) {
                if (similarity(tokens, representativeTokens.get(group)) >= effective) {
                    matched = group;
                    break;
                }
            }

            if (matched >= 0) {
                memberGroups.get(matched).add(index);
            } else {
                representativeTokens.add(tokens);
                List<Integer> members = new ArrayList<>();
                members.add(index);
                memberGroups.add(members);
            }
        }

        List<Cluster> clusters = new ArrayList<>(memberGroups.size());
        for (List<Integer> members : memberGroups) {
            // 成员里最先出现的那个就是代表元：输入已排序，所以它就是"被问得最多的那条"
            clusters.add(new Cluster(members.get(0), List.copyOf(members)));
        }
        return clusters;
    }

    /**
     * 把一条问题切成 2-gram 集合。
     *
     * <p>先做归一化（去空白、去标点、统一小写），所以"赠品 什么时候 发货"
     * 与"赠品什么时候发货"切出来的集合是一样的 —— 排版差异不该影响聚类结果。
     *
     * @param question 问题原文
     * @return 2-gram 集合；归一化后为空时返回空集合
     */
    private static Set<String> tokenize(String question) {
        String normalized = HashUtils.normalizeQuestion(question);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        if (normalized.length() < NGRAM_SIZE) {
            // 单个字（"退"）也要有自己的表示，否则会被当成空问题丢掉
            tokens.add(normalized);
            return tokens;
        }
        for (int start = 0; start + NGRAM_SIZE <= normalized.length(); start++) {
            tokens.add(normalized.substring(start, start + NGRAM_SIZE));
        }
        return tokens;
    }

    /**
     * 计算两个 2-gram 集合的 Jaccard 相似度。
     *
     * <p>用 Jaccard 而不是余弦：两边的集合大小本来就不同（长短问句），
     * Jaccard 天然按并集归一，短问句不会被长问句稀释掉。
     *
     * @param left  集合 A
     * @param right 集合 B
     * @return 相似度，0~1
     */
    private static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        // 遍历较小的集合，减少比较次数
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        for (String token : smaller) {
            if (larger.contains(token)) {
                intersection++;
            }
        }
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * 一个簇。
     *
     * @param representativeIndex 代表元在输入列表中的下标（即簇标题对应的那条问题）
     * @param memberIndexes       全部成员（含代表元）在输入列表中的下标
     */
    public record Cluster(int representativeIndex, List<Integer> memberIndexes) {

        /**
         * 该簇的规模。
         *
         * @return 成员数量
         */
        public int size() {
            return memberIndexes.size();
        }
    }
}
