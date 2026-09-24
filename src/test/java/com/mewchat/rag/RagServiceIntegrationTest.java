package com.mewchat.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mewchat.dao.mysql.entity.KnowledgeChunk;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.rag.document.DocumentIngestRequest;
import com.mewchat.rag.document.DocumentIngestService;
import com.mewchat.rag.retrieval.RetrievalResult;
import com.mewchat.rag.retrieval.RetrievedChunk;
import com.mewchat.service.KnowledgeChunkService;
import com.mewchat.service.KnowledgeDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索链路集成测试（需要真实 MySQL）。
 *
 * <p><b>为什么必须用真实 MySQL</b>：BM25 依赖两件 MySQL 特有的能力 ——
 * ngram 全文索引（中文能否被召回）与 {@code MATCH ... AGAINST} 的 df 统计。
 * 换成 H2 这两件事都验不到，测试会在错误的前提下变成假绿灯。
 *
 * <p><b>本测试刻意不加 {@code @Transactional} 回滚</b>，这一点很重要：
 * InnoDB 的全文索引<b>在事务提交时才更新</b>，未提交的新行对
 * {@code MATCH ... AGAINST} 不可见。若沿用其它集成测试那种"事务内回滚"的写法，
 * 刚入库的切片会搜不到，测试必然失败 —— 而这个失败反映的是测试环境与生产环境的
 * 差异，不是代码缺陷。因此这里改为真实提交，并在 {@link #cleanUp()} 中清理数据。
 *
 * <p><b>每个用例使用独立的唯一词</b>：数据是真实提交的，若多个用例共用同一个检索词，
 * 彼此的结果会互相干扰。唯一词让每个用例只可能召回自己写入的切片。
 *
 * <p><b>运行在 Milvus 未启用的前提下</b>，因此覆盖的正是"向量通道不可用、
 * 降级为纯关键词检索"这条路径。向量通道需要真实 Milvus 才能验证。
 *
 * <p>默认不执行，需显式开启：
 * <pre>
 * ./mvnw test -Dmewchat.it.mysql=true \
 *     -Dmewchat.it.mysql.url="jdbc:mysql://127.0.0.1:3306/mewchat?useSSL=false&amp;allowPublicKeyRetrieval=true"
 * </pre>
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${mewchat.it.mysql.username:root}",
        "spring.datasource.password=${mewchat.it.mysql.password:}",
        "spring.datasource.url=${mewchat.it.mysql.url:jdbc:mysql://127.0.0.1:3306/mewchat"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        // 令牌密钥必须显式提供：application.yml 故意没有默认值（见该处注释）
        "mewchat.auth.token-secret=it-test-secret-0123456789abcdef"
})
@EnabledIfSystemProperty(named = "mewchat.it.mysql", matches = "true")
class RagServiceIntegrationTest {

    /** 孤儿切片用例使用的、不存在的文档ID */
    private static final long ORPHAN_DOC_ID = 999_999_999_999L;

    @Autowired
    private RagService ragService;

    @Autowired
    private DocumentIngestService ingestService;

    @Autowired
    private KnowledgeChunkService chunkService;

    @Autowired
    private KnowledgeDocumentService documentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本用例专属的检索词 */
    private String uniqueTerm;

    /** 已创建的文档ID，用于用例结束后清理 */
    private final List<Long> createdDocIds = new ArrayList<>();

    @BeforeEach
    void prepareUniqueTerm() {
        uniqueTerm = "qx" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @AfterEach
    void cleanUp() {
        // 数据是真实提交的，必须清理。这里用物理删除而不是走 ingestService.delete()：
        // 后者对文档是逻辑删除，会在开发库里不断累积 deleted=1 的残行。
        // 测试的职责是"跑完不留痕"，而不是复现业务删除语义。
        createdDocIds.forEach(docId -> {
            jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE doc_id = ?", docId);
            jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", docId);
        });
        createdDocIds.clear();
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE doc_id = ?", ORPHAN_DOC_ID);
    }

    /**
     * 入库应完成分片并写入切片表，段落号连续、长度与偏移一致。
     */
    @Test
    void ingestShouldSplitAndPersistChunks() {
        Long docId = ingestDoc("入库测试文档");

        List<KnowledgeChunk> chunks = listChunks(docId);
        assertThat(chunks).isNotEmpty();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            assertThat(chunk.getChunkNo()).as("段落号应从 1 开始连续递增").isEqualTo(i + 1);
            assertThat(chunk.getContentLength()).isEqualTo(chunk.getContent().length());
            assertThat(chunk.getCharStart()).isNotNull();
        }
    }

    /**
     * 关键词检索应命中正确文档，并带回可溯源的完整引用信息。
     *
     * <p>这是"知识问答有出处"的核心断言：不仅要召回内容，还必须带出
     * 文档ID、文档标题、段落号，否则前端无法展示"参考来源"。
     */
    @Test
    void searchShouldRetrieveByKeywordWithTraceableSource() {
        String title = "退换货规则-" + UUID.randomUUID();
        Long docId = ingestDoc(title);

        RetrievalResult result = ragService.search(uniqueTerm);

        assertThat(result.isEmpty()).isFalse();
        RetrievedChunk top = result.getChunks().get(0);
        assertThat(top.getDocId()).isEqualTo(docId);
        assertThat(top.getDocTitle()).as("引用来源必须带文档标题").isEqualTo(title);
        assertThat(top.getChunkNo()).as("引用来源必须带段落号").isPositive();
        assertThat(top.getText()).contains(uniqueTerm);
        assertThat(top.getChunkId()).isEqualTo(docId + "_" + top.getChunkNo());
        assertThat(top.getScore()).isBetween(0.0, 1.0);
        assertThat(top.getSources()).contains("bm25");
        // Milvus 未启用，向量通道命中数应为 0，这也印证了降级路径确实生效
        assertThat(result.getVectorHitCount()).isZero();
        assertThat(result.getBm25HitCount()).isPositive();
    }

    /**
     * 置信度应随命中情况变化：命中时为正，完全无命中时为 0。
     */
    @Test
    void confidenceShouldReflectHitAndMiss() {
        ingestDoc("置信度测试文档-" + UUID.randomUUID());

        RetrievalResult hit = ragService.search(uniqueTerm);
        assertThat(hit.getConfidence())
                .as("有关键词命中时置信度应为正")
                .isGreaterThan(BigDecimal.ZERO);

        // 另一个不存在的唯一词：既无关键词命中，向量通道也不可用
        RetrievalResult miss = ragService.search("zz" + UUID.randomUUID().toString().replace("-", ""));
        assertThat(miss.isEmpty()).isTrue();
        assertThat(miss.getConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 来源文档已不存在的孤儿切片必须被剔除。
     *
     * <p>对应真实故障：Milvus 删除失败会残留向量，文档却已经删了。
     * 这类片段无法展示出处、也不该计入置信度，必须在检索结果里过滤掉，
     * 而不是让用户看到一条"查不到来源"的回答依据。
     */
    @Test
    void orphanChunksShouldBeFilteredOut() {
        // 造一条 docId 指向不存在文档的切片（直接写 MySQL，模拟残留数据）
        chunkService.replaceChunks(ORPHAN_DOC_ID, List.of(KnowledgeChunk.builder()
                .docId(ORPHAN_DOC_ID)
                .chunkNo(1)
                .content(buildContent())
                .contentLength(buildContent().length())
                .build()));

        RetrievalResult result = ragService.search(uniqueTerm);

        assertThat(result.isEmpty())
                .as("来源文档已不存在的切片不应出现在检索结果中")
                .isTrue();
        assertThat(result.getConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 重新入库应整体替换切片，而不是叠加出重复段落。
     */
    @Test
    void reindexShouldNotDuplicateChunks() {
        Long docId = ingestDoc("重复入库测试文档");
        int firstCount = listChunks(docId).size();

        ingestService.reindex(docId);

        assertThat(listChunks(docId))
                .as("重新入库应整体替换切片，(doc_id, chunk_no) 唯一键也不允许重复")
                .hasSize(firstCount);
    }

    /**
     * 向量库未启用时，文档状态应如实标记为失败并说明原因，
     * 但关键词索引必须已经可用 —— 这是"降级而非不可用"的关键行为。
     */
    @Test
    void ingestShouldMarkFailedButKeepKeywordIndexWhenMilvusDisabled() {
        Long docId = ingestDoc("Milvus 未启用测试文档");

        KnowledgeDocument document = documentService.getById(docId);
        assertThat(document.getEmbedStatus()).isEqualTo(3);
        assertThat(document.getEmbedError()).contains("向量库未启用");
        assertThat(document.getChunkCount()).isPositive();

        // 尽管状态是失败，关键词检索依然能召回到它
        assertThat(ragService.search(uniqueTerm).isEmpty()).isFalse();
    }

    /**
     * 删除文档应同时清掉切片与文档记录，并立即不再被检索到。
     *
     * <p>这条链路容易漏：切片在 MySQL 和 Milvus 各存一份，
     * 只删一边就会留下"文档没了但还能搜到"的幽灵结果。
     */
    @Test
    void deleteShouldRemoveChunksAndDocument() {
        Long docId = ingestDoc("删除测试文档");
        assertThat(listChunks(docId)).isNotEmpty();
        assertThat(ragService.search(uniqueTerm).isEmpty()).isFalse();

        ingestService.delete(docId);
        // 已在业务层删除，无需再进清理列表
        createdDocIds.remove(docId);

        assertThat(listChunks(docId)).isEmpty();
        assertThat(documentService.getById(docId)).as("文档应已被逻辑删除").isNull();
        assertThat(ragService.search(uniqueTerm).isEmpty())
                .as("文档删除后不应再被检索到")
                .isTrue();

        // 上面验证的正是业务删除（逻辑删除，会留下 deleted=1 的行）。
        // 断言完成后把它物理清掉，避免每跑一次测试就在开发库里留一行残迹
        jdbcTemplate.update("DELETE FROM knowledge_document WHERE id = ?", docId);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 入库一篇包含唯一检索词的测试文档。
     *
     * @param title 文档标题
     * @return 文档ID
     */
    private Long ingestDoc(String title) {
        Long docId = ingestService.ingest(DocumentIngestRequest.builder()
                .title(title)
                .category("测试")
                .content(buildContent())
                .build());
        createdDocIds.add(docId);
        return docId;
    }

    /**
     * 查询某文档的全部切片，按段落号升序。
     *
     * @param docId 文档ID
     * @return 切片列表
     */
    private List<KnowledgeChunk> listChunks(Long docId) {
        return chunkService.list(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getDocId, docId)
                .orderByAsc(KnowledgeChunk::getChunkNo));
    }

    /**
     * 构造一段包含唯一检索词、且足够长到会被切成多片的正文。
     *
     * @return 文档正文
     */
    private String buildContent() {
        return "第一条规则：签收之日起七天内，商品完好可以申请无理由退货。\n"
                + "第二条规则：" + uniqueTerm + " 属于定制类商品，不支持无理由退货。\n"
                + "第三条规则：退货运费由责任方承担，非质量问题由买家承担。\n"
                + "第四条规则：退款将在审核通过后原路返回，一般一到三个工作日到账。\n"
                + "第五条规则：换货需保证商品及包装完好，配件齐全，不影响二次销售。\n";
    }
}
