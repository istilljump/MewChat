package com.mewchat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索配置，对应 {@code application.yml} 的 {@code mewchat.rag.*}。
 *
 * @author MewChat
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mewchat.rag")
public class RagProperties {

    /**
     * 最终返回给调用方的片段条数。
     *
     * <p>这是经过融合与重排后送入提示词的数量。调大能提高召回率，
     * 但会把更多无关片段塞给模型，反而干扰生成、推高 token 成本。
     */
    private int topK = 5;

    /**
     * 向量通道的相似度下限，低于该值的候选直接丢弃。
     *
     * <p>COSINE 度量下取值 0~1。设得过低会把不相关内容当作依据，
     * 让模型一本正经地胡说；设得过高则容易召回为空、频繁走兜底。
     */
    private double minScore = 0.5;

    /**
     * 单通道候选召回倍数。
     *
     * <p>两个通道各召回 {@code topK × 本值} 条，再融合、重排、截断到 topK。
     * 召回阶段必须比最终需求宽：融合与重排只能在已召回的池子里挑，
     * 池子太小会把正确结果直接排除在外。
     */
    private int candidateMultiplier = 3;

    /**
     * 补充检索的召回倍数。
     *
     * <p>置信度落在中档（见 {@code mewchat.agent.high/low-confidence-threshold}）时，
     * 编排层会让检索再跑一次：这一次把最终片段数放大到 {@code topK × 本值}，
     * 目的是用更宽的召回池把依据补足 —— 首次检索可能因为池子偏小，
     * 把本该作为依据的片段排在门外。
     *
     * <p>编排层保证<b>只补检索一次</b>。若允许反复放宽，就成了"不断降低标准直到满意"，
     * 置信度也就失去了意义。
     */
    private int retryTopKMultiplier = 2;

    /** 单个切片送入模型的最大字符数，防止超长片段挤占上下文 */
    private int maxChunkChars = 1000;

    /** BM25 关键词通道参数 */
    private Bm25 bm25 = new Bm25();

    /** RRF 融合参数 */
    private Rrf rrf = new Rrf();

    /** 置信度计算参数 */
    private Confidence confidence = new Confidence();

    /** 文档分片参数 */
    private Chunking chunking = new Chunking();

    /**
     * BM25 参数。
     */
    @Getter
    @Setter
    public static class Bm25 {

        /**
         * 词频饱和系数 k1。
         *
         * <p>控制"某个词出现很多次"带来的增益上限。取值越大，词频的影响越持久；
         * 1.2 是文献里的常用默认值。
         */
        private double k1 = 1.2;

        /**
         * 长度归一化系数 b。
         *
         * <p>0 表示不做长度归一化（长文档天然占优），1 表示完全归一化。
         * 0.75 是常用默认值：既抑制长文档的优势，又不完全抹平长度差异。
         */
        private double b = 0.75;

        /**
         * 查询词项数量上限。
         *
         * <p>每个词项都要发一次文档频率统计查询，因此必须封顶。
         * 一般查询切出 10 个以内的 2-gram 已足够表达意图。
         */
        private int maxQueryTerms = 12;

        /**
         * 候选条数上限。
         *
         * <p>关键词候选是从全文索引捞出来的，只做上限保护，靠 BM25 排序取前 N。
         */
        private int maxCandidates = 200;
    }

    /**
     * RRF 融合参数。
     */
    @Getter
    @Setter
    public static class Rrf {

        /**
         * 平滑参数 k。
         *
         * <p>越大则"排在第一"的优势越平缓，各路结果的影响越均衡；
         * 越小则越倾向于信任各路的头部结果。60 是 RRF 原论文的推荐取值。
         */
        private int k = 60;
    }

    /**
     * 置信度计算参数。
     */
    @Getter
    @Setter
    public static class Confidence {

        /** 最高分权重，与 {@link #countWeight} 之和建议为 1 */
        private double topScoreWeight = 0.7;

        /** 命中数量权重 */
        private double countWeight = 0.3;

        /**
         * 期望的有效片段数。
         *
         * <p>达到这个数量，数量因子就拿满分。设得太大会让置信度普遍偏低、
         * 频繁走兜底；设得太小则数量因子形同虚设。
         *
         * <p><b>默认取 1</b>，依据用算式即可说明（置信度 = 0.7×最高分
         * + 0.3×min(1, 有效片段数/本值)）：
         * <ul>
         *     <li>取 3 时，单片命中（新知识库与具体问题最常见的形态）要让置信度够到
         *         达标线 0.70，需要重排分不低于 0.857，换算成词项覆盖度约 0.89 ——
         *         等于要求"提问的二元词几乎全都出现在这一片里"。阶段 12 端到端联调实测：
         *         一条精准命中的片段（引用正确、重排分 0.714、覆盖度 0.29）算出 0.60，
         *         落进中档、白跑一次补检索后兜底，而答案就在手上</li>
         *     <li>取 1 之后，零词面覆盖的无关片段依然进不来：它的重排分恰好等于融合项下限
         *         0.5，置信度上界 = 0.7×0.5 + 0.3 = 0.65 &lt; 0.70 ——
         *         "排第一"本身仍不足以作答，必须有词面或标题上的实质匹配</li>
         * </ul>
         */
        private int expectedChunkCount = 1;

        /**
         * 相关性下限。
         *
         * <p>最终分低于该值的片段不计入"有效片段数" ——
         * 一堆低分片段不该把置信度抬上去。
         */
        private double relevanceFloor = 0.5;
    }

    /**
     * 文档分片参数。
     */
    @Getter
    @Setter
    public static class Chunking {

        /**
         * 目标切片长度（字符）。
         *
         * <p>太小会把一句完整的话切碎、语义不完整；太大则检索命中后带入大量无关内容。
         * 中文场景 400~600 字是比较均衡的区间。
         */
        private int chunkSize = 500;

        /**
         * 相邻切片的重叠字符数。
         *
         * <p>必须留重叠：关键句子正好落在切分点上时，不留重叠会导致两个切片
         * 都只拿到半句话，检索时谁都匹配不上。
         */
        private int overlap = 50;

        /**
         * 单切片长度硬上限。
         *
         * <p>双保险：既防止异常输入（例如一整段没有标点）撑爆上下文，
         * 也保证不超过 Milvus text 字段的 65535 字符上限。
         */
        private int maxChunkSize = 1200;
    }
}
