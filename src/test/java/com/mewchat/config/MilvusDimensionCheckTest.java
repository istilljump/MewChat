package com.mewchat.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量维度一致性校验的单元测试（不依赖 Spring 与 Milvus 服务）。
 *
 * <p><b>为什么要专门测这一条</b>：它是接 Milvus 之后第一个会让人白忙半天的坑 ——
 * 集合按固定维度创建，维度对不上时向量写入失败，而错误信息来自向量库、
 * 指向不了"是两个配置项没对齐"。更麻烦的是集合建错了只能删掉重建。
 * 因此这个校验必须在<b>启动阶段</b>就给出结论，而不能等第一次写入。
 *
 * @author MewChat
 */
class MilvusDimensionCheckTest {

    private final MilvusConfig config = new MilvusConfig();

    /**
     * 两处维度一致时放行。
     */
    @Test
    void matchingDimensionShouldPass() {
        MilvusProperties milvus = milvusProperties(1024);
        LlmProperties llm = llmProperties(1024);

        assertThatCode(() -> config.checkDimension(milvus, llm)).doesNotThrowAnyException();
    }

    /**
     * 两处维度不一致时必须启动失败，且错误信息要能直接指出该改哪里。
     */
    @Test
    void mismatchedDimensionShouldFailStartup() {
        MilvusProperties milvus = milvusProperties(1024);
        LlmProperties llm = llmProperties(1536);

        assertThatThrownBy(() -> config.checkDimension(milvus, llm))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mewchat.llm.embedding.dimensions=1536")
                .hasMessageContaining("mewchat.milvus.dimension=1024")
                .hasMessageContaining("重建");
    }

    /**
     * 未配置 embedding 维度时不失败 —— 有些模型的维度是固定的，我们无从判断，
     * 不该替用户拍板把应用拦住。
     */
    @Test
    void absentEmbeddingDimensionShouldNotFail() {
        MilvusProperties milvus = milvusProperties(1024);
        LlmProperties llm = llmProperties(null);

        assertThatCode(() -> config.checkDimension(milvus, llm)).doesNotThrowAnyException();
    }

    /**
     * 校验不应发起任何远端调用。
     *
     * <p>这一点靠"签名里没有 EmbeddingModel"来保证：{@code EmbeddingModel.dimension()}
     * 的默认实现会真的 embed 一段文本，把它作为参数传进来就等于把一次付费调用
     * 放进了启动路径。这里用<b>编译期即可表达</b>的方式钉住这个约束。
     */
    @Test
    void checkShouldOnlyCompareConfiguration() {
        assertThat(MilvusConfig.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("checkDimension"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .as("维度校验只能比对配置项，不能引入需要远端调用的模型参数")
                        .containsExactly(MilvusProperties.class, LlmProperties.class));
    }

    /**
     * 构造 Milvus 配置。
     *
     * @param dimension 维度
     * @return 配置对象
     */
    private static MilvusProperties milvusProperties(int dimension) {
        MilvusProperties properties = new MilvusProperties();
        properties.setDimension(dimension);
        return properties;
    }

    /**
     * 构造大模型配置。
     *
     * @param dimension embedding 维度，可为 null 表示未配置
     * @return 配置对象
     */
    private static LlmProperties llmProperties(Integer dimension) {
        LlmProperties properties = new LlmProperties();
        properties.getEmbedding().setDimensions(dimension);
        return properties;
    }
}
