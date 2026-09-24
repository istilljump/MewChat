package com.mewchat.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问题聚类的单元测试（纯单元测试）。
 *
 * <p>这里要钉死两件事：<b>该归并的能归并</b>，以及<b>不该归并的不要乱归</b>。
 * 后者的代价更大 —— 两个不相干的问题被塞进一簇，运营会照着一个错误的主题去补文档，
 * 补完发现没解决任何问题。
 *
 * <p>另外，本算法用的是<b>字面</b>相似度（2-gram Jaccard），
 * 因此测试用例都是"问法接近"的问题，而不是同义改写的问句 ——
 * 后者这个算法本来就归不动，那是 embedding 的活（见类注释）。
 *
 * @author MewChat
 */
class QuestionClustererTest {

    /** 默认阈值，与 application.yml 保持一致 */
    private static final double THRESHOLD = 0.55;

    /**
     * 问法只差一两个字（同一个问题的不同说法）应归为一簇。
     */
    @Test
    void nearIdenticalQuestionsShouldClusterTogether() {
        List<String> questions = List.of(
                "赠品什么时候发货",
                "赠品什么时候发货呢",
                "赠品什么时候发货啊");

        List<QuestionClusterer.Cluster> clusters = QuestionClusterer.cluster(questions, THRESHOLD);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).size()).isEqualTo(3);
        assertThat(clusters.get(0).representativeIndex())
                .as("代表元应是排在最前的那条（调用方按命中次数降序传入）")
                .isZero();
    }

    /**
     * 不同的问题不能被混进一簇。
     */
    @Test
    void differentQuestionsShouldStayApart() {
        List<String> questions = List.of(
                "赠品什么时候发货",
                "发票怎么开",
                "退款多久到账");

        List<QuestionClusterer.Cluster> clusters = QuestionClusterer.cluster(questions, THRESHOLD);

        assertThat(clusters).hasSize(3);
        assertThat(clusters).allSatisfy(cluster -> assertThat(cluster.size()).isEqualTo(1));
    }

    /**
     * 同义改写归不动 —— 这是本算法<b>已知的</b>局限，把它写进测试是为了让后来者
     * 一眼看到边界在哪，而不是以为"聚类坏了"。
     */
    @Test
    void paraphrasesAreKnownLimitation() {
        List<String> questions = List.of(
                "赠品什么时候发货",
                "赠品何时寄出");

        List<QuestionClusterer.Cluster> clusters = QuestionClusterer.cluster(questions, THRESHOLD);

        assertThat(clusters)
                .as("字面相似度归不动同义改写；要做到得换 embedding（见 QuestionClusterer 类注释）")
                .hasSize(2);
    }

    /**
     * 排版差异（空白、标点）不影响聚类：归一化之后再比。
     */
    @Test
    void punctuationAndSpacingShouldNotMatter() {
        List<String> questions = List.of(
                "发票怎么开？",
                "发票  怎么开");

        List<QuestionClusterer.Cluster> clusters = QuestionClusterer.cluster(questions, THRESHOLD);

        assertThat(clusters).hasSize(1);
    }

    /**
     * 归一化后为空的问题（纯标点、空白）不参与聚类。
     *
     * <p>它们若不排除，会凭"空集合与空集合完全相似"聚成一大簇，
     * 在清单上表现为一个莫名其妙的大主题。
     */
    @Test
    void emptyQuestionsShouldBeIgnored() {
        List<String> questions = List.of("！！！", "   ", "发票怎么开", "发票怎么开呢");

        List<QuestionClusterer.Cluster> clusters = QuestionClusterer.cluster(questions, THRESHOLD);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).memberIndexes())
                .as("被忽略的问题不应出现在任何簇里")
                .containsExactly(2, 3);
    }

    /**
     * 单个字的问题也要能用（不能因为切不出 2-gram 就被当成空问题丢掉）。
     */
    @Test
    void singleCharacterQuestionShouldStillBeUsable() {
        List<QuestionClusterer.Cluster> clusters =
                QuestionClusterer.cluster(List.of("退", "退"), THRESHOLD);

        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).size()).isEqualTo(2);
    }

    /**
     * 阈值可调：调高应当更保守（同一批问题被拆成更多簇）。
     */
    @Test
    void higherThresholdShouldClusterLess() {
        List<String> questions = List.of("赠品什么时候发货", "赠品什么时候发货呢");

        assertThat(QuestionClusterer.cluster(questions, 0.95)).hasSize(2);
        assertThat(QuestionClusterer.cluster(questions, 0.55)).hasSize(1);
    }

    /**
     * 结果必须是确定的：同样的输入必然得到同样的分簇。
     *
     * <p>这条不是形式要求 —— 聚类是贪心的（先到的问题成为代表元），
     * 一旦结果随调用而变，运营看到的"待优化清单"就会今天一个样明天一个样，
     * 前一天标记过的主题第二天找不到了。
     */
    @Test
    void clusteringShouldBeDeterministic() {
        List<String> questions = List.of(
                "赠品什么时候发货", "赠品什么时候发货呢", "发票怎么开", "发票怎么开啊");

        List<QuestionClusterer.Cluster> first = QuestionClusterer.cluster(questions, THRESHOLD);
        List<QuestionClusterer.Cluster> second = QuestionClusterer.cluster(questions, THRESHOLD);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(cluster -> assertThat(cluster.size()).isEqualTo(2));
    }

    /**
     * 空输入不抛异常。
     */
    @Test
    void emptyInputShouldReturnNoClusters() {
        assertThat(QuestionClusterer.cluster(List.of(), THRESHOLD)).isEmpty();
        assertThat(QuestionClusterer.cluster(null, THRESHOLD)).isEmpty();
    }
}
