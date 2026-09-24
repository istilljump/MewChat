package com.mewchat.rag.document;

import com.mewchat.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分片器。
 *
 * <p><b>分片质量直接决定 RAG 的上限</b>：切片太碎，语义不完整，检索到了也答不好；
 * 切片太长，命中后带入大量无关内容，既干扰模型又浪费 token。因此这里做了三层控制：
 * <ol>
 *     <li><b>按句子边界切</b> —— 先切句，再把句子合并到目标长度。
 *         绝不在句子中间断开，否则"签收后七天内可以"和"无理由退货"
 *         会落在两个切片里，谁都检索不到完整规则</li>
 *     <li><b>相邻切片留重叠</b> —— 关键句子正好压在切分点上时不至于两边各拿半句</li>
 *     <li><b>硬上限兜底</b> —— 输入里可能出现整段没有标点的异常内容（如代码、
 *         无标点的长串），必须有强制切分，否则单个切片会撑爆上下文、
 *         也会超过 Milvus 字段的字符上限</li>
 * </ol>
 *
 * <p>返回的片段带原文起止偏移，供前端做"命中内容高亮"。
 *
 * @author MewChat
 */
@Component
public class DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(DocumentSplitter.class);

    /** 句末标点：中英文的句号、问号、感叹号、分号，以及换行 */
    private static final String BOUNDARY_CHARS = "。！？；!?;\n";

    /** 配置缺失或取值非法时的兜底目标长度 */
    private static final int FALLBACK_CHUNK_SIZE = 500;

    /** 低于该长度会显著影响切片语义完整性的告警阈值 */
    private static final int MIN_SENSIBLE_CHUNK_SIZE = 50;

    private final RagProperties ragProperties;

    public DocumentSplitter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 把文档全文切分为片段。
     *
     * @param text 文档全文
     * @return 片段列表，保持原文顺序；空文本返回空列表
     */
    public List<Piece> split(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        RagProperties.Chunking config = ragProperties.getChunking();
        int chunkSize = resolveChunkSize(config);
        int maxChunkSize = Math.max(chunkSize, config.getMaxChunkSize());
        // 重叠不能超过半个目标长度：否则相邻切片大半重复，既浪费存储也让检索结果高度同质
        int overlap = Math.max(0, Math.min(config.getOverlap(), chunkSize / 2));

        List<int[]> units = splitIntoUnits(text, maxChunkSize);
        List<int[]> groups = mergeIntoChunks(units, chunkSize);
        List<Piece> pieces = applyOverlap(text, groups, overlap);

        log.debug("文档分片完成：原文 {} 字，切出 {} 片（目标长度 {}，重叠 {}）",
                text.length(), pieces.size(), chunkSize, overlap);
        return pieces;
    }

    /**
     * 解析目标切片长度。
     *
     * <p>只对"非法取值"兜底，不对"偏小但合法"的取值做静默修正 ——
     * 悄悄把配置改掉会让人以为配置生效了，实际没有。
     * 取值过小时只发告警，把判断权留给使用者。
     *
     * @param config 分片配置
     * @return 目标切片长度
     */
    private int resolveChunkSize(RagProperties.Chunking config) {
        if (config.getChunkSize() <= 0) {
            log.warn("mewchat.rag.chunking.chunk-size 取值非法（{}），回退为默认值 {}",
                    config.getChunkSize(), FALLBACK_CHUNK_SIZE);
            return FALLBACK_CHUNK_SIZE;
        }
        if (config.getChunkSize() < MIN_SENSIBLE_CHUNK_SIZE) {
            log.warn("mewchat.rag.chunking.chunk-size={} 偏小，切出的片段可能语义不完整，建议不小于 {}",
                    config.getChunkSize(), MIN_SENSIBLE_CHUNK_SIZE);
        }
        return config.getChunkSize();
    }

    /**
     * 先按句子切分，再把超长句强制切分，保证每个单元的边长都不超过硬上限。
     *
     * @param text         文档全文
     * @param maxChunkSize 硬上限
     * @return 单元区间列表，每项为 [start, end)，左闭右开
     */
    private List<int[]> splitIntoUnits(String text, int maxChunkSize) {
        List<int[]> units = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (BOUNDARY_CHARS.indexOf(text.charAt(i)) >= 0) {
                appendUnit(units, start, i + 1, maxChunkSize);
                start = i + 1;
            }
        }
        if (start < text.length()) {
            appendUnit(units, start, text.length(), maxChunkSize);
        }
        return units;
    }

    /**
     * 追加一个单元；若该单元超过硬上限则按上限强制切分。
     *
     * @param units        输出列表
     * @param start        起始偏移
     * @param end          结束偏移
     * @param maxChunkSize 硬上限
     */
    private void appendUnit(List<int[]> units, int start, int end, int maxChunkSize) {
        if (end <= start) {
            return;
        }
        if (end - start <= maxChunkSize) {
            units.add(new int[]{start, end});
            return;
        }
        // 整段没有标点（代码块、无标点长串）时的兜底：按长度硬切
        for (int position = start; position < end; position += maxChunkSize) {
            units.add(new int[]{position, Math.min(position + maxChunkSize, end)});
        }
    }

    /**
     * 把相邻单元合并到目标长度。
     *
     * <p>贪心策略：能装下就继续装，装不下就另起一片。
     * 不做"回填找最优组合"，因为分片是被检索消费的中间产物，
     * 长度略有不均不影响效果，没必要为此提高复杂度。
     *
     * @param units     单元区间
     * @param chunkSize 目标长度
     * @return 合并后的区间列表
     */
    private List<int[]> mergeIntoChunks(List<int[]> units, int chunkSize) {
        List<int[]> groups = new ArrayList<>();
        int currentStart = -1;
        int currentEnd = -1;

        for (int[] unit : units) {
            if (currentStart < 0) {
                currentStart = unit[0];
                currentEnd = unit[1];
                continue;
            }
            if (unit[1] - currentStart <= chunkSize) {
                currentEnd = unit[1];
            } else {
                groups.add(new int[]{currentStart, currentEnd});
                currentStart = unit[0];
                currentEnd = unit[1];
            }
        }
        if (currentStart >= 0) {
            groups.add(new int[]{currentStart, currentEnd});
        }
        return groups;
    }

    /**
     * 给相邻切片加上重叠内容。
     *
     * <p>做法是把每一片（第一片除外）的起点往前挪 {@code overlap} 个字符。
     * 第二遍处理而不是合并时直接做，是为了让"合并"只关心语义完整性、
     * "重叠"只关心边界连续性，两件事互不干扰。
     *
     * @param text    文档全文
     * @param groups  合并后的区间
     * @param overlap 重叠字符数
     * @return 最终片段
     */
    private List<Piece> applyOverlap(String text, List<int[]> groups, int overlap) {
        List<Piece> pieces = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            int[] group = groups.get(i);
            int start = i == 0 ? group[0] : Math.max(0, group[0] - overlap);
            pieces.add(new Piece(text.substring(start, group[1]), start, group[1]));
        }
        return pieces;
    }

    /**
     * 一个文档片段。
     *
     * @param text  片段原文
     * @param start 在文档全文中的起始偏移（含）
     * @param end   在文档全文中的结束偏移（不含）
     */
    public record Piece(String text, int start, int end) {
    }
}
