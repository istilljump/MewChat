package com.mewchat.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分词器测试（纯单元测试）。
 *
 * <p>分词粒度必须与 MySQL ngram 索引的 {@code ngram_token_size=2} 对齐，
 * 否则 BM25 的文档频率统计会失真。这里把这层约定固化下来。
 *
 * @author MewChat
 */
class ChunkTokenizerTest {

    private static final int MAX_TERMS = 20;

    private final ChunkTokenizer tokenizer = new ChunkTokenizer();

    /**
     * 中文应按 2 元切分，与索引粒度一致。
     */
    @Test
    void chineseShouldBeSplitIntoBigrams() {
        assertThat(tokenizer.tokenize("退货政策", MAX_TERMS))
                .containsExactly("退货", "货政", "政策");
    }

    /**
     * 单个汉字无法在 2-gram 索引里定位，应当被丢弃。
     *
     * <p>这是 ngram_token_size=2 的固有限制：留着它只会让文档频率恒为 0，
     * 白白多一次统计查询。
     */
    @Test
    void singleChineseCharacterShouldBeDropped() {
        assertThat(tokenizer.tokenize("退", MAX_TERMS)).isEmpty();
        assertThat(tokenizer.tokenize("能退吗", MAX_TERMS)).containsExactly("能退", "退吗");
    }

    /**
     * 英文按单词切分并转小写；长度 1 的词丢弃。
     */
    @Test
    void latinShouldBeSplitByWord() {
        assertThat(tokenizer.tokenize("Refund Policy a", MAX_TERMS))
                .containsExactly("refund", "policy");
    }

    /**
     * 中英文混排时两类片段应各自正确处理。
     */
    @Test
    void mixedTextShouldHandleBothScripts() {
        assertThat(tokenizer.tokenize("退货 refund", MAX_TERMS))
                .containsExactly("退货", "refund");
    }

    /**
     * 标点与空白必须作为分隔符，不能跨越它们组成词项。
     *
     * <p>跨标点组词（例如把"货，还"切成"货还"）会产出索引里根本不存在的词项：
     * ngram 索引是按原文连续字符切分的，带标点的词组永远不会被匹配到，
     * 只会让 df 恒为 0、白白多一次统计查询。
     */
    @Test
    void punctuationShouldActAsSeparator() {
        List<String> terms = tokenizer.tokenize("退货，还是换货？", MAX_TERMS);
        assertThat(terms).containsExactly("退货", "还是", "是换", "换货");
        assertThat(terms).noneMatch(term -> term.contains("，") || term.contains("？"));
    }

    /**
     * 重复词项应去重：BM25 对词项求和，重复查询词没有意义，
     * 去重后还能少发几次统计查询。
     */
    @Test
    void duplicateTermsShouldBeDeduplicated() {
        assertThat(tokenizer.tokenize("退货退货", MAX_TERMS))
                .containsExactly("退货", "货退");
    }

    /**
     * 词项数量应受上限约束。
     */
    @Test
    void termsShouldRespectMaxLimit() {
        assertThat(tokenizer.tokenize("一二三四五六七八九十", 3)).hasSize(3);
    }

    /**
     * "分隔符触发中途落笔"这条路径上，上限同样必须成立。
     *
     * <p>中文片段是一整段连着 flush 的，只有遇到标点/空白才落笔。若上限只在文本收尾时
     * 裁剪，一段带标点的长问句就会在第一个标点处一次性写入上百个二元词 ——
     * 上限形同虚设，而下游会为一个词项发一次 df 统计查询（见 Bm25Retriever），
     * 一句带标点的普通中文提问就能打出上百条 SQL。
     */
    @Test
    void maxLimitShouldHoldWhenSeparatorFlushesMidway() {
        String longCjkThenSpace = "一二三四五六七八九十百千万亿元角分厘毫丝忽微纤沙尘埃渺"
                + "莫茫静默幽深远处高低温差变化" + " " + "后续内容";

        assertThat(tokenizer.tokenize(longCjkThenSpace, 3))
                .as("标点/空白触发的中途落笔也必须守住上限")
                .hasSizeLessThanOrEqualTo(3);
        assertThat(tokenizer.tokenize("一二三四五 六", 3)).hasSize(3);
    }

    /**
     * 空输入应返回空列表而不是抛异常。
     */
    @Test
    void blankInputShouldReturnEmpty() {
        assertThat(tokenizer.tokenize(null, MAX_TERMS)).isEmpty();
        assertThat(tokenizer.tokenize("", MAX_TERMS)).isEmpty();
        assertThat(tokenizer.tokenize("   ", MAX_TERMS)).isEmpty();
        assertThat(tokenizer.tokenize("退货", 0)).isEmpty();
    }

    /**
     * 拼查询串时应以空格分隔，且不含 BOOLEAN MODE 的操作符。
     *
     * <p>这一点同时是安全保证：词项只可能由中日韩文字与字母数字组成，
     * 因此拼接出的查询串无法被注入 {@code + - > < ( ) ~ * "} 这些操作符。
     */
    @Test
    void booleanQueryShouldBeSpaceSeparatedAndFreeOfOperators() {
        String query = tokenizer.buildBooleanQuery(tokenizer.tokenize("退货+政策-(规则)", MAX_TERMS));
        assertThat(query).doesNotContain("+", "-", "(", ")");
        assertThat(query.split(" ")).isNotEmpty();
    }

    /**
     * 词项包含判断应可用于计算覆盖度。
     */
    @Test
    void containsTermShouldDetectSubstring() {
        assertThat(tokenizer.containsTerm("签收后七天内可退货", "退货")).isTrue();
        assertThat(tokenizer.containsTerm("签收后七天内可退货", "换货")).isFalse();
        assertThat(tokenizer.containsTerm(null, "退货")).isFalse();
        assertThat(tokenizer.containsTerm("签收", null)).isFalse();
    }
}
