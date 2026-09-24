package com.mewchat.rag.retrieval;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索用的轻量分词器。
 *
 * <p><b>为什么自己写而不引第三方分词器</b>：BM25 的词项必须与 MySQL 全文索引的
 * 切分粒度一致。本项目在 {@code knowledge_chunk} 上用的是 MySQL 内置 ngram 解析器，
 * 且 {@code ngram_token_size=2}（按 2 元切分）。若引入 jieba 之类的分词器，
 * 切出来的词与索引里的 2-gram 对不上，"文档频率 df" 就统计不出来，BM25 会失真。
 * 因此这里对齐索引粒度，中文按 2 元切分，英文按单词切分。
 *
 * <p><b>顺带的作用是防注入</b>：输出的词项只包含中日韩文字与字母数字，
 * BOOLEAN MODE 的操作符（{@code + - > < ( ) ~ * "}）根本不可能出现在结果里，
 * 因此把词拼成查询串时不需要额外转义。
 *
 * <p><b>已知限制</b>：单个汉字的查询词会被丢弃 —— ngram_token_size=2 的索引里
 * 不存在长度为 1 的词项，留着它只会让 df 恒为 0。用户只输入一个字时
 * 关键词通道会返回空，此时仍可靠向量通道召回。
 *
 * @author MewChat
 */
@Component
public class ChunkTokenizer {

    /** 与 MySQL ngram_token_size 保持一致，改动时两者必须同步 */
    private static final int NGRAM_SIZE = 2;

    /**
     * 切分文本为检索词项。
     *
     * <p><b>上限必须在"写词项的地方"生效，不能只在收尾时裁剪。</b>
     * 中文片段是一整段连着 flush 的（只有遇到分隔符或文本结束才落笔），
     * 中途那次 {@code terms.size() >= maxTerms} 检查发生在 flush <b>之后</b>：
     * 一段 40 字的连续中文会一次性写进 39 个二元词，检查当场看到的是 39 而不是上限。
     * 于是"上限"形同虚设，下游会为一个词项发一次 df 统计查询
     * （见 {@code Bm25Retriever}），一段带标点的长问题就能打出上百条 SQL。
     * 因此 {@code maxTerms} 一路传到 flush 里，写满即停。
     *
     * @param text     待切分文本
     * @param maxTerms 词项数量上限，避免超长输入产生大量 df 统计查询
     * @return 去重且保持出现顺序的词项列表，长度不超过 maxTerms
     */
    public List<String> tokenize(String text, int maxTerms) {
        if (!StringUtils.hasText(text) || maxTerms <= 0) {
            return List.of();
        }

        // LinkedHashSet 兼顾去重与顺序：BM25 对词项求和，重复的查询词没有意义，
        // 去重后也能少发几次 df 统计查询
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder latinRun = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();
        int length = text.length();

        for (int offset = 0; offset < length; ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (isCjk(codePoint)) {
                flushLatin(latinRun, terms, maxTerms);
                cjkRun.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushCjk(cjkRun, terms, maxTerms);
                latinRun.appendCodePoint(Character.toLowerCase(codePoint));
            } else {
                // 标点、空白等作为分隔符：中文与英文的交界处也因此被切开
                flushLatin(latinRun, terms, maxTerms);
                flushCjk(cjkRun, terms, maxTerms);
            }
            if (terms.size() >= maxTerms) {
                return new ArrayList<>(terms);
            }
        }
        flushLatin(latinRun, terms, maxTerms);
        flushCjk(cjkRun, terms, maxTerms);

        // 收尾裁剪是兜底：flush 已经各自守住上限，这里正常不会再有超出
        List<String> result = new ArrayList<>(terms);
        return result.size() > maxTerms ? result.subList(0, maxTerms) : result;
    }

    /**
     * 把英文/数字片段作为一个词项输出。
     *
     * @param latinRun 待处理的字母数字片段
     * @param terms    输出集合
     * @param maxTerms 词项数量上限
     */
    private void flushLatin(StringBuilder latinRun, Set<String> terms, int maxTerms) {
        if (latinRun.length() >= 2 && terms.size() < maxTerms) {
            terms.add(latinRun.toString());
        }
        latinRun.setLength(0);
    }

    /**
     * 把中日韩片段按 2 元切分后输出。
     *
     * <p>例如"退货政策"产出"退货""货政""政策"三个词项 ——
     * 与 ngram 索引的切分方式一致，df 统计才有意义。
     *
     * <p>写满 {@code maxTerms} 立即停止：不在这里守住上限的话，
     * 一段连续中文会在这一次 flush 里把上百个词项一次性写进来，
     * 调用方的"词项上限"就只剩一个说法（见 {@link #tokenize}）。
     *
     * @param cjkRun   待处理的中日韩片段
     * @param terms    输出集合
     * @param maxTerms 词项数量上限
     */
    private void flushCjk(StringBuilder cjkRun, Set<String> terms, int maxTerms) {
        int length = cjkRun.length();
        if (length >= NGRAM_SIZE) {
            for (int i = 0; i + NGRAM_SIZE <= length; i++) {
                if (terms.size() >= maxTerms) {
                    break;
                }
                terms.add(cjkRun.substring(i, i + NGRAM_SIZE));
            }
        }
        cjkRun.setLength(0);
    }

    /**
     * 判断是否为中日韩文字。
     *
     * @param codePoint 码点
     * @return true 表示汉字、假名或谚文
     */
    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * 把词项拼成 MySQL BOOLEAN MODE 查询串。
     *
     * <p>空格分隔即 OR 语义。召回阶段用 OR 是有意的：宁可多召回再由 BM25 与重排
     * 筛掉，也不要在召回阶段漏掉可能相关的片段。
     *
     * @param terms 词项列表
     * @return BOOLEAN MODE 查询串
     */
    public String buildBooleanQuery(List<String> terms) {
        return terms == null ? "" : String.join(" ", terms);
    }

    /**
     * 判断词项是否出现在文本中（大小写不敏感，面向中文无需特殊处理）。
     *
     * <p>用于重排时计算查询词覆盖度。
     *
     * @param text 文本
     * @param term 词项
     * @return true 表示出现
     */
    public boolean containsTerm(String text, String term) {
        if (text == null || term == null || term.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }
}
