package com.mewchat.config;

import com.mewchat.dao.milvus.MilvusVectorDao;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Milvus 相关 Bean 配置。
 *
 * <p>整个类由 {@code mewchat.milvus.enabled} 一个开关控制：关闭时下述 Bean 全部不创建，
 * 应用不触碰任何向量库依赖，因此本机未部署 Milvus 也能正常启动。
 *
 * <p>把 {@link MilvusVectorDao} 也放在这里声明（而不是给它加 {@code @Component}），
 * 是为了让"谁能存在、依赖什么"集中在一处：DAO 依赖 {@link EmbeddingStore}，
 * 若条件分散在两个类里，改漏一处就会出现"DAO 存在但 store 不存在"的启动失败。
 *
 * @author MewChat
 */
@Configuration
@ConditionalOnProperty(prefix = "mewchat.milvus", name = "enabled", havingValue = "true")
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    /**
     * Milvus 官方 Java SDK 客户端。
     *
     * <p>创建的只是 gRPC 通道，真正的连接在首次调用时建立，
     * 所以 Milvus 暂时不可用不会在启动阶段就炸掉整个应用。
     *
     * <p>需要做集合级运维（建索引、查统计、改配置）时可直接注入本 Bean；
     * 常规的向量增删查请走 {@link MilvusVectorDao}。
     *
     * @param properties Milvus 配置
     * @return SDK 客户端
     */
    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(MilvusProperties properties) {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withConnectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS);
        if (StringUtils.hasText(properties.getUsername())) {
            builder.withAuthorization(properties.getUsername(), properties.getPassword());
        }

        log.info("MilvusServiceClient 初始化：host={} port={}", properties.getHost(), properties.getPort());
        return new MilvusServiceClient(builder.build());
    }

    /**
     * 向量存储实现，复用上面创建的唯一一个客户端。
     *
     * <p>复用而非再建一个：{@code MilvusEmbeddingStore} 若通过 host/port 自行创建，
     * 会多出一条 gRPC 通道，连接数与资源都翻倍。
     *
     * <p>集合由 {@code MilvusEmbeddingStore} 在首次使用时按固定 schema 自动创建，
     * <b>不要手工预建</b>，见 {@link MilvusProperties} 的说明。
     *
     * @param properties    Milvus 配置
     * @param milvusClient  已创建的 SDK 客户端
     * @param llmProperties 大模型配置，仅用于校验向量维度是否与配置一致
     * @return 向量存储实现
     */
    @Bean
    public EmbeddingStore<TextSegment> milvusEmbeddingStore(MilvusProperties properties,
                                                            MilvusServiceClient milvusClient,
                                                            LlmProperties llmProperties) {
        checkDimension(properties, llmProperties);

        EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                .milvusClient(milvusClient)
                .collectionName(properties.getCollectionName())
                .dimension(properties.getDimension())
                .indexType(parseIndexType(properties.getIndexType()))
                .metricType(parseMetricType(properties.getMetricType()))
                .idFieldName(properties.getIdFieldName())
                .textFieldName(properties.getTextFieldName())
                .metadataFieldName(properties.getMetadataFieldName())
                .vectorFieldName(properties.getVectorFieldName())
                .build();

        log.info("Milvus 向量存储就绪：collection={} dimension={} index={} metric={}",
                properties.getCollectionName(), properties.getDimension(),
                properties.getIndexType(), properties.getMetricType());
        return store;
    }

    /**
     * 向量库访问封装。
     *
     * @param embeddingStore 向量存储实现
     * @param embeddingModel 向量化模型
     * @return 向量库 DAO
     */
    @Bean
    public MilvusVectorDao milvusVectorDao(EmbeddingStore<TextSegment> embeddingStore,
                                           EmbeddingModel embeddingModel) {
        return new MilvusVectorDao(embeddingStore, embeddingModel);
    }

    /**
     * 解析索引类型。
     *
     * <p>枚举的 {@code valueOf} 区分大小写，这里先统一转大写，
     * 并在取值非法时把可选值列进错误信息，避免只报一句 "No enum constant"。
     *
     * @param value 配置中的索引类型
     * @return 索引类型枚举
     */
    private IndexType parseIndexType(String value) {
        try {
            return IndexType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "mewchat.milvus.index-type 取值非法：" + value + "，可选值为 "
                            + Arrays.stream(IndexType.values()).map(Enum::name).collect(Collectors.joining("/")),
                    e);
        }
    }

    /**
     * 解析度量方式，处理方式同 {@link #parseIndexType(String)}。
     *
     * @param value 配置中的度量方式
     * @return 度量方式枚举
     */
    private MetricType parseMetricType(String value) {
        try {
            return MetricType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "mewchat.milvus.metric-type 取值非法：" + value + "，可选值为 "
                            + Arrays.stream(MetricType.values()).map(Enum::name).collect(Collectors.joining("/")),
                    e);
        }
    }

    /**
     * 校验向量维度配置是否自洽。
     *
     * <p>维度不一致是这套技术栈里最容易犯且最难查的错误：集合按固定维度创建，
     * 写入时直接失败（或更糟：写入成功但检索结果莫名其妙），
     * 而且建好集合后维度改不了、只能重建。因此在启动阶段就比对一次。
     *
     * <p><b>比的是两个配置项，而不是去问模型</b>：{@code EmbeddingModel.dimension()}
     * 的默认实现就是"真的 embed 一段文本再数向量长度"，那会<b>在启动时发起一次
     * 真实的付费调用</b>，并且拿不到值时只能降级成一条日志（于是校验形同虚设）。
     * 配置项之间比对是确定性的、零成本、且失败即失败 ——
     * 与本项目"Milvus 客户端只建通道、不在启动阶段连服务"是同一个原则。
     *
     * <p>发现不一致时<b>直接启动失败</b>：这不是"可能有问题"，而是"这套配置跑不起来"。
     * 只在日志里告警的话，问题会在第一次写入向量时才暴露，而那时集合可能已经建错了。
     *
     * <p>未配置 {@code dimensions} 时不失败，只提醒 —— 有些厂商的模型维度是固定的、
     * 不需要显式声明，此时我们无从判断，不该替用户拍板。
     *
     * @param properties    Milvus 配置
     * @param llmProperties 大模型配置
     * @throws IllegalStateException 两处维度配置不一致时抛出
     */
    void checkDimension(MilvusProperties properties, LlmProperties llmProperties) {
        Integer embeddingDimension = llmProperties.getEmbedding().getDimensions();
        if (embeddingDimension == null) {
            log.warn("mewchat.llm.embedding.dimensions 未配置，无法校验它与 "
                            + "mewchat.milvus.dimension({}) 是否一致。集合按固定维度创建，"
                            + "维度不一致会导致向量写入失败且错误指向不了配置项，"
                            + "建议按所用 embedding 模型的输出维度显式配置该值",
                    properties.getDimension());
            return;
        }
        if (embeddingDimension != properties.getDimension()) {
            throw new IllegalStateException(
                    "向量维度不一致：mewchat.llm.embedding.dimensions=" + embeddingDimension
                            + " 但 mewchat.milvus.dimension=" + properties.getDimension()
                            + "。二者必须相同，否则向量写入会失败；"
                            + "若集合已按旧维度建好，还需删除集合并重建");
        }
        log.info("向量维度校验通过：embedding 与 Milvus 均为 {} 维", embeddingDimension);
    }
}
