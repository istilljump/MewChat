package com.mewchat.dao.mysql;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mewchat.dao.mysql.entity.Conversation;
import com.mewchat.dao.mysql.entity.KnowledgeDocument;
import com.mewchat.dao.mysql.entity.LowConfidenceQuestion;
import com.mewchat.dao.mysql.entity.Message;
import com.mewchat.dao.mysql.entity.MessageRefDoc;
import com.mewchat.dao.mysql.entity.Ticket;
import com.mewchat.dao.mysql.entity.User;
import com.mewchat.dao.mysql.mapper.ConversationMapper;
import com.mewchat.dao.mysql.mapper.KnowledgeDocumentMapper;
import com.mewchat.dao.mysql.mapper.LowConfidenceQuestionMapper;
import com.mewchat.dao.mysql.mapper.MessageMapper;
import com.mewchat.dao.mysql.mapper.TicketMapper;
import com.mewchat.dao.mysql.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体与数据库表结构的映射验证（需要真实 MySQL）。
 *
 * <p><b>为什么需要这个测试</b>：实体里的字段名与表列名之间靠隐式规则转换
 * （camelCase ↔ snake_case），写错一个字母编译器不会报错，
 * 直到运行时才抛 "Unknown column"。而"插进去再查出来"能一次性覆盖
 * 列名映射、主键生成、审计字段填充、JSON 字段转换、逻辑删除这几件最容易出错的事。
 *
 * <p><b>默认不执行</b>：只有显式传入系统属性 {@code -Dmewchat.it.mysql=true}
 * 才会运行，因此 {@code mvn test} 在没有 MySQL 的机器上依然是绿的。
 *
 * <p>前置条件：MySQL 中已存在 {@code mewchat} 库，且已执行
 * {@code sql/01_schema.sql}。默认连本地 3306，也可用系统属性覆盖：
 * <pre>
 * ./mvnw test -Dmewchat.it.mysql=true \
 *     -Dmewchat.it.mysql.url="jdbc:mysql://127.0.0.1:3399/mewchat?useSSL=false&amp;allowPublicKeyRetrieval=true"
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
// 每个用例结束后回滚，不在你的开发库里留下测试数据
@Transactional
class MysqlSchemaMappingTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private LowConfidenceQuestionMapper lowConfidenceQuestionMapper;

    /**
     * 用户表：主键生成 + 审计字段自动填充 + 逻辑删除。
     */
    @Test
    void userShouldRoundTripWithFilledAuditFieldsAndLogicDelete() {
        User user = User.builder()
                .username("it_user_" + System.nanoTime())
                .password("$2a$10$placeholderplaceholderplaceholderplaceholder")
                .nickname("集成测试账号")
                .userType(1)
                .status(1)
                .build();

        assertThat(userMapper.insert(user)).isEqualTo(1);

        // 主键由雪花算法生成，且 MetaObjectHandler 应已填充两个审计字段
        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreateTime()).isNotNull();
        assertThat(user.getUpdateTime()).isNotNull();

        User loaded = userMapper.selectById(user.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo(user.getUsername());
        assertThat(loaded.getNickname()).isEqualTo("集成测试账号");
        assertThat(loaded.getUserType()).isEqualTo(1);
        assertThat(loaded.getDeleted()).isEqualTo(0);

        // 逻辑删除：deleteById 实际执行 UPDATE ... SET deleted=1，随后查询应查不到
        assertThat(userMapper.deleteById(user.getId())).isEqualTo(1);
        assertThat(userMapper.selectById(user.getId())).isNull();
    }

    /**
     * 会话表：验证冗余统计字段与 default 值。
     */
    @Test
    void conversationShouldRoundTrip() {
        Conversation conversation = Conversation.builder()
                .sessionId("it-session-" + System.nanoTime())
                .title("集成测试会话")
                .status(1)
                .startTime(LocalDateTime.now())
                .messageCount(0)
                .build();

        assertThat(conversationMapper.insert(conversation)).isEqualTo(1);
        assertThat(conversation.getId()).isNotNull();

        Conversation loaded = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getSessionId, conversation.getSessionId()));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTitle()).isEqualTo("集成测试会话");
        assertThat(loaded.getStatus()).isEqualTo(1);
        assertThat(loaded.getCreateTime()).isNotNull();
        assertThat(loaded.getDeleted()).isEqualTo(0);
        // user_id 未设置且列可为空，应保持为空（游客会话）
        assertThat(loaded.getUserId()).isNull();

        conversationMapper.deleteById(conversation.getId());
    }

    /**
     * 消息表：重点验证 JSON 列 {@code ref_docs} 的读写。
     *
     * <p>这一项最容易出错：{@code @TableName} 若漏了 {@code autoResultMap = true}，
     * 写入正常但查询回来会是未解析的字符串，甚至直接抛类型转换异常。
     * 同时验证本表没有 updateTime 字段时，审计字段填充不会报错。
     */
    @Test
    void messageShouldRoundTripJsonRefDocs() {
        String sessionId = "it-session-msg-" + System.nanoTime();
        List<MessageRefDoc> refDocs = List.of(
                MessageRefDoc.builder()
                        .chunkId("1727138400000000001_3")
                        .docId(1727138400000000001L)
                        .docTitle("七天无理由退换货规则")
                        .chunkNo(3)
                        .score(0.9123)
                        .used(1)
                        .build(),
                MessageRefDoc.builder()
                        .chunkId("1727138400000000001_4")
                        .docId(1727138400000000001L)
                        .docTitle("七天无理由退换货规则")
                        .chunkNo(4)
                        .score(0.8451)
                        .used(0)
                        .build()
        );

        Message message = Message.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content("根据平台规则，签收后 7 天内可申请无理由退货。")
                .costMs(1234)
                .promptTokens(320)
                .completionTokens(48)
                .totalTokens(368)
                .modelName("deepseek-chat")
                .agentName("AfterSaleAgent")
                .confidence(new BigDecimal("0.9123"))
                .refDocs(refDocs)
                .status(1)
                .build();

        assertThat(messageMapper.insert(message)).isEqualTo(1);
        assertThat(message.getId()).isNotNull();
        // 本表无 updateTime 字段，填充处理器应跳过而不是报错
        assertThat(message.getCreateTime()).isNotNull();

        Message loaded = messageMapper.selectById(message.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getContent()).isEqualTo(message.getContent());
        assertThat(loaded.getAgentName()).isEqualTo("AfterSaleAgent");
        assertThat(loaded.getConfidence()).isEqualByComparingTo(new BigDecimal("0.9123"));
        assertThat(loaded.getTotalTokens()).isEqualTo(368);

        // JSON 往返：两个片段的结构与取值都应完整保留
        assertThat(loaded.getRefDocs()).hasSize(2);
        MessageRefDoc first = loaded.getRefDocs().get(0);
        assertThat(first.getChunkId()).isEqualTo("1727138400000000001_3");
        assertThat(first.getDocId()).isEqualTo(1727138400000000001L);
        assertThat(first.getChunkNo()).isEqualTo(3);
        assertThat(first.getScore()).isEqualTo(0.9123);
        assertThat(first.getUsed()).isEqualTo(1);
        assertThat(loaded.getRefDocs().get(1).getUsed()).isZero();

        messageMapper.deleteById(message.getId());
    }

    /**
     * 知识库文档表：验证 LONGTEXT 正文与向量化状态字段。
     */
    @Test
    void knowledgeDocumentShouldRoundTrip() {
        String content = "第一条规则：签收后 7 天内可申请无理由退货。\n第二条规则：定制商品不支持无理由退货。";

        KnowledgeDocument document = KnowledgeDocument.builder()
                .title("七天无理由退换货规则")
                .content(content)
                .category("退换货")
                .fileName("return-policy.md")
                .fileType("md")
                .fileSize((long) content.length())
                .chunkCount(0)
                .embedStatus(0)
                .build();

        assertThat(knowledgeDocumentMapper.insert(document)).isEqualTo(1);
        assertThat(document.getId()).isNotNull();

        KnowledgeDocument loaded = knowledgeDocumentMapper.selectById(document.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getContent()).isEqualTo(content);
        assertThat(loaded.getCategory()).isEqualTo("退换货");
        // 注意：docId 是 19 位雪花ID，这里同时验证它不会在 Long 上丢精度
        assertThat(loaded.getId()).isEqualTo(document.getId());
        assertThat(loaded.getEmbedStatus()).isZero();

        knowledgeDocumentMapper.deleteById(document.getId());
    }

    /**
     * 工单表：验证可空外键与状态字段。
     */
    @Test
    void ticketShouldRoundTrip() {
        User customer = User.builder()
                .username("it_ticket_user_" + System.nanoTime())
                .password("$2a$10$placeholderplaceholderplaceholderplaceholder")
                .nickname("提单用户")
                .userType(1)
                .status(1)
                .build();
        userMapper.insert(customer);

        Ticket ticket = Ticket.builder()
                .sessionId("it-session-ticket-" + System.nanoTime())
                .userId(customer.getId())
                .type("refund")
                .description("用户申请退款，但订单状态不支持，需人工核实")
                .status(0)
                .build();

        assertThat(ticketMapper.insert(ticket)).isEqualTo(1);

        Ticket loaded = ticketMapper.selectById(ticket.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo(customer.getId());
        assertThat(loaded.getType()).isEqualTo("refund");
        assertThat(loaded.getStatus()).isZero();
        // 处理人与完成时间此时应为空
        assertThat(loaded.getHandlerId()).isNull();
        assertThat(loaded.getFinishTime()).isNull();

        ticketMapper.deleteById(ticket.getId());
        userMapper.deleteById(customer.getId());
    }

    /**
     * 低置信度问题池：验证唯一键去重语义所需的字段，以及"无逻辑删除"的物理删除行为。
     */
    @Test
    void lowConfidenceQuestionShouldRoundTripAndPhysicallyDelete() {
        String hash = String.format("%064x", Math.abs(System.nanoTime()));

        LowConfidenceQuestion question = LowConfidenceQuestion.builder()
                .question("你们支持货到付款吗")
                .questionHash(hash)
                .confidence(new BigDecimal("0.3120"))
                .hitCount(1)
                .sessionId("it-session-lcq-" + System.nanoTime())
                .optimized(0)
                .build();

        assertThat(lowConfidenceQuestionMapper.insert(question)).isEqualTo(1);
        assertThat(question.getId()).isNotNull();

        LowConfidenceQuestion loaded = lowConfidenceQuestionMapper.selectOne(
                new LambdaQueryWrapper<LowConfidenceQuestion>()
                        .eq(LowConfidenceQuestion::getQuestionHash, hash));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getHitCount()).isEqualTo(1);
        assertThat(loaded.getConfidence()).isEqualByComparingTo(new BigDecimal("0.3120"));
        assertThat(loaded.getOptimized()).isZero();

        // 本表没有逻辑删除字段，deleteById 应为物理删除
        assertThat(lowConfidenceQuestionMapper.deleteById(question.getId())).isEqualTo(1);
        assertThat(lowConfidenceQuestionMapper.selectById(question.getId())).isNull();
    }
}
