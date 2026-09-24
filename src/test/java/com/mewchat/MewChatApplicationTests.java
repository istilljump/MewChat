package com.mewchat;

import com.mewchat.dao.mysql.mapper.ConversationMapper;
import com.mewchat.dao.mysql.mapper.KnowledgeDocumentMapper;
import com.mewchat.dao.mysql.mapper.LowConfidenceQuestionMapper;
import com.mewchat.dao.mysql.mapper.MessageMapper;
import com.mewchat.dao.mysql.mapper.TicketMapper;
import com.mewchat.dao.mysql.mapper.UserMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用启动冒烟测试。
 *
 * <p>作用：完整加载 Spring 上下文，验证"项目能正常启动不报错"这一验收点。
 * 上下文能加载起来，意味着：自动配置无冲突、各配置类的 Bean 定义合法、
 * Security 过滤链可构建、MyBatis-Plus 能初始化。
 *
 * <p>这里把数据源换成 H2 内存库，原因：本机 MySQL 服务需要管理员权限才能启动，
 * 而"能否连上 MySQL"属于环境问题、不属于代码问题。用 H2 隔离掉环境依赖后，
 * 该测试在任何机器上都能稳定运行，可长期保留在 CI 中。
 *
 * <p>注意：H2 只用于验证"Bean 装配是否正确"，<b>不验证 SQL 与表结构的匹配</b>
 * ——建表脚本是 MySQL 方言，H2 跑不了。那部分由 {@code MysqlSchemaMappingTest}
 * 在真实 MySQL 上覆盖。
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mewchat;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        // 令牌密钥必须显式提供：application.yml 故意没有默认值（见该处注释）
        "mewchat.auth.token-secret=it-test-secret-0123456789abcdef"
})
class MewChatApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 上下文加载成功即通过；加载失败会抛异常导致测试失败。
     */
    @Test
    void contextLoads() {
        // 无需断言：@SpringBootTest 在上下文加载失败时即判定失败
    }

    /**
     * 验证大模型接入配置生效。
     *
     * <p>这里断言的是 LangChain4j 的通用接口而非具体的 {@code OpenAiChatModel}，
     * 这样将来换成通义千问、智谱等专用实现时，只要它们仍实现这些接口，本测试无需改动。
     *
     * <p>Bean 的创建只做配置校验，不会真正发起网络请求，因此用占位 api-key 也能通过。
     */
    @Test
    void aiModelBeansShouldBeCreated() {
        assertThat(applicationContext.getBeansOfType(ChatModel.class))
                .as("mewchat.llm.chat 应产出一个 ChatModel Bean")
                .isNotEmpty();

        assertThat(applicationContext.getBeansOfType(StreamingChatModel.class))
                .as("mewchat.llm.chat 应产出一个 StreamingChatModel Bean，SSE 流式输出依赖它")
                .isNotEmpty();

        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class))
                .as("mewchat.llm.embedding 应产出一个 EmbeddingModel Bean，RAG 依赖它")
                .isNotEmpty();
    }

    /**
     * 验证 6 张表的 Mapper 都被 {@code @MapperScan} 扫描并注册。
     *
     * <p>这类问题很隐蔽：接口漏了继承 {@code BaseMapper}、或包路径不在扫描范围内，
     * 都不会编译报错，而是等到注入时才抛 {@code NoSuchBeanDefinitionException}。
     */
    @Test
    void allMappersShouldBeRegistered() {
        Map<String, Object> mappers = Map.of(
                "UserMapper", applicationContext.getBean(UserMapper.class),
                "ConversationMapper", applicationContext.getBean(ConversationMapper.class),
                "MessageMapper", applicationContext.getBean(MessageMapper.class),
                "KnowledgeDocumentMapper", applicationContext.getBean(KnowledgeDocumentMapper.class),
                "TicketMapper", applicationContext.getBean(TicketMapper.class),
                "LowConfidenceQuestionMapper", applicationContext.getBean(LowConfidenceQuestionMapper.class)
        );

        assertThat(mappers).hasSize(6).allSatisfy((name, mapper) ->
                assertThat(mapper).as(name + " 应已注册为 Bean").isNotNull());
    }
}
