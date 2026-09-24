package com.mewchat.rag.document;

import com.mewchat.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档分片器测试（纯单元测试）。
 *
 * <p>用固定长度的句子构造输入，便于手算期望结果。
 *
 * @author MewChat
 */
class DocumentSplitterTest {

    /** 四个等长句子，每句 5 个字，全文 20 字 */
    private static final String TEXT = "第一句话。第二句话。第三句话。第四句话。";

    /**
     * 空输入返回空列表。
     */
    @Test
    void blankInputShouldReturnEmpty() {
        DocumentSplitter splitter = splitter(100, 200, 0);
        assertThat(splitter.split(null)).isEmpty();
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split("   ")).isEmpty();
    }

    /**
     * 短文本应只切出一片。
     */
    @Test
    void shortTextShouldProduceSinglePiece() {
        DocumentSplitter splitter = splitter(100, 200, 0);

        List<DocumentSplitter.Piece> pieces = splitter.split(TEXT);

        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).text()).isEqualTo(TEXT);
        assertThat(pieces.get(0).start()).isZero();
        assertThat(pieces.get(0).end()).isEqualTo(TEXT.length());
    }

    /**
     * 相邻句子应合并到目标长度，且不在句子中间断开。
     */
    @Test
    void sentencesShouldBeMergedUpToChunkSize() {
        // 目标长度 10：两句刚好 10 字，因此应为 2 片，每片两句
        DocumentSplitter splitter = splitter(10, 50, 0);

        List<DocumentSplitter.Piece> pieces = splitter.split(TEXT);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0).text()).isEqualTo("第一句话。第二句话。");
        assertThat(pieces.get(1).text()).isEqualTo("第三句话。第四句话。");
    }

    /**
     * 相邻切片应保留重叠内容。
     *
     * <p>重叠是为了兜住"关键句子正好压在切分点上"的情况：
     * 不留重叠时，压线的句子会被切成两半，两个切片都匹配不上它。
     */
    @Test
    void adjacentChunksShouldOverlap() {
        DocumentSplitter splitter = splitter(10, 50, 3);

        List<DocumentSplitter.Piece> pieces = splitter.split(TEXT);

        assertThat(pieces).hasSize(2);
        // 第二片起点应比自然边界（第 10 个字符）提前 3 个字符
        assertThat(pieces.get(1).start()).isEqualTo(7);
        assertThat(pieces.get(1).text()).startsWith(TEXT.substring(7, 10));
        assertThat(pieces.get(1).text()).endsWith("第四句话。");
    }

    /**
     * 偏移必须与原文对得上，否则前端高亮会错位。
     */
    @Test
    void offsetsShouldMatchOriginalText() {
        DocumentSplitter splitter = splitter(10, 50, 3);

        for (DocumentSplitter.Piece piece : splitter.split(TEXT)) {
            assertThat(piece.text())
                    .as("片段文本必须等于原文对应区间，否则高亮会错位")
                    .isEqualTo(TEXT.substring(piece.start(), piece.end()));
        }
    }

    /**
     * 整段没有标点时必须按硬上限强制切分。
     *
     * <p>代码块、无标点长串都可能触发这条路径；没有这个兜底，
     * 单个切片会撑爆上下文，也会超过向量库字段的字符上限。
     */
    @Test
    void punctuationFreeTextShouldBeForceSplit() {
        String text = "A".repeat(120);
        DocumentSplitter splitter = splitter(10, 50, 0);

        List<DocumentSplitter.Piece> pieces = splitter.split(text);

        assertThat(pieces).hasSize(3);
        assertThat(pieces).extracting(piece -> piece.end() - piece.start())
                .as("每片都不得超过硬上限")
                .allSatisfy(length -> assertThat(length).isLessThanOrEqualTo(50));
        // 强制切分后拼回原文应完全一致，确保没有丢字
        assertThat(String.join("", pieces.stream().map(DocumentSplitter.Piece::text).toList()))
                .isEqualTo(text);
    }

    /**
     * 重叠长度不应超过目标长度的一半。
     *
     * <p>否则相邻切片大半重复：既浪费存储，又让检索结果高度同质
     * （同一段内容反复出现在多条结果里，把真正的其他依据挤出去）。
     */
    @Test
    void overlapShouldBeCappedAtHalfOfChunkSize() {
        // 目标长度 10，请求重叠 20，实际应被压到 5
        DocumentSplitter splitter = splitter(10, 50, 20);

        List<DocumentSplitter.Piece> pieces = splitter.split(TEXT);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(1).start()).isEqualTo(5);
    }

    /**
     * 构造使用指定分片参数的分片器。
     *
     * @param chunkSize    目标长度
     * @param maxChunkSize 硬上限
     * @param overlap      重叠字符数
     * @return 分片器
     */
    private DocumentSplitter splitter(int chunkSize, int maxChunkSize, int overlap) {
        RagProperties properties = new RagProperties();
        properties.getChunking().setChunkSize(chunkSize);
        properties.getChunking().setMaxChunkSize(maxChunkSize);
        properties.getChunking().setOverlap(overlap);
        return new DocumentSplitter(properties);
    }
}
