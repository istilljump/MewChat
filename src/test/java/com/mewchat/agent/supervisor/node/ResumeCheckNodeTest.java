package com.mewchat.agent.supervisor.node;

import com.mewchat.agent.ChatContext;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.IntentType;
import com.mewchat.config.AgentProperties;
import com.mewchat.dao.mysql.entity.PendingClarification;
import com.mewchat.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 澄清续接的单元测试（纯单元测试，不依赖数据库）。
 *
 * <p>它验证"断点续接"这条要求的落点：用户被追问后回一个"1"，
 * 该被还原成具体订单并<b>直接进工具层</b>（跳过意图识别与路由），
 * 而不是被当成一句听不懂的话再问一遍。
 *
 * <p>同样重要的是<b>不该续接的那些输入</b>：用户如果去问了别的事、
 * 或者干脆自己把订单号打出来，都应当走正常流程。
 * 误续接的表现比不续接更糟 —— 用户问"这个订单能退吗"，
 * 系统却径直去查了订单状态，答非所问且毫无解释。
 *
 * @author MewChat
 */
class ResumeCheckNodeTest {

    private static final String SESSION = "test-session";

    private ConversationService conversationService;

    private ResumeCheckNode node;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        node = new ResumeCheckNode(conversationService, new AgentProperties());
    }

    /* ==================== 应当续接 ==================== */

    /**
     * 阿拉伯数字序号：还原成候选值，直接进工具层。
     */
    @Test
    void ordinalShouldResumeIntoToolCall() {
        givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now()));
        ChatContext context = contextOf("1");

        assertThat(node.execute(context)).isEqualTo(ChatState.TOOL_CALL);

        assertThat(context.getIntent()).isEqualTo(IntentType.ORDER_QUERY);
        assertThat(context.getParams()).containsEntry("orderNo", "MC202409240001");
        assertThat(context.getIntentConfidence())
                .as("意图是上一轮识别过的，用户只是确认了候选项，应给满分")
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(context.effectiveQuery())
                .as("必须改写查询文本：指代消解会把'1'原样返回，"
                        + "不覆盖的话模型看到的用户问题就是一个数字")
                .contains("MC202409240001")
                .contains("我的订单到哪了");
        verify(conversationService).clearPendingClarification(SESSION);
    }

    /**
     * 第二个候选要被正确对应到第二个值（下标从 1 起，不能差一位）。
     */
    @Test
    void secondOrdinalShouldMapToSecondOption() {
        givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now()));
        ChatContext context = contextOf("2");

        assertThat(node.execute(context)).isEqualTo(ChatState.TOOL_CALL);
        assertThat(context.getParams()).containsEntry("orderNo", "MC202409240002");
    }

    /**
     * 序号的各种包裹写法都要认得：括号、顿号、中文数词。
     */
    @Test
    void ordinalVariantsShouldResume() {
        for (String message : List.of("1)", "1、", "第1个", "第一个", "一", " 1 ")) {
            givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now()));
            ChatContext context = contextOf(message);

            assertThat(node.execute(context))
                    .as("'%s' 应被识别为选中第一个候选", message)
                    .isEqualTo(ChatState.TOOL_CALL);
            assertThat(context.getParams()).containsEntry("orderNo", "MC202409240001");
        }
    }

    /**
     * 续接保留的是<b>原来的意图</b>，不是固定当成订单查询。
     */
    @Test
    void resumeShouldKeepOriginalIntent() {
        givenPending(pending(IntentType.LOGISTICS_QUERY, LocalDateTime.now()));
        ChatContext context = contextOf("1");

        assertThat(node.execute(context)).isEqualTo(ChatState.TOOL_CALL);
        assertThat(context.getIntent()).isEqualTo(IntentType.LOGISTICS_QUERY);
    }

    /* ==================== 不该续接 ==================== */

    /**
     * 用户自己把订单号打出来时，走正常流程即可 —— 意图识别能正确抽取 orderNo。
     *
     * <p>刻意不把它当成"选中候选"：否则用户接着问"MC202409240001 这个能退吗"，
     * 会被误判成"他选了第一个订单"而直接去查订单状态，答非所问。
     */
    @Test
    void orderNumberTypedDirectlyShouldGoThroughNormalFlow() {
        givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now()));
        ChatContext context = contextOf("MC202409240001");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
        assertThat(context.getParams()).as("不应把值填进参数").isEmpty();
        verify(conversationService).clearPendingClarification(SESSION);
    }

    /**
     * 用户去问别的事时，清掉挂起状态并按新问题走。
     *
     * <p>不清的话，他之后随口回一个"1"会被续接回这个早已过时的追问。
     */
    @Test
    void newQuestionShouldClearPendingAndGoToIntent() {
        givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now()));
        ChatContext context = contextOf("算了，我想问下退货怎么操作");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
        verify(conversationService).clearPendingClarification(SESSION);
    }

    /**
     * 序号越界不猜：宁可走一遍新流程，也不要猜一个候选出来。
     */
    @Test
    void outOfRangeOrdinalShouldNotResume() {
        givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now()));
        ChatContext context = contextOf("9");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
    }

    /**
     * 挂起状态过期后不再续接 —— 半小时前问的"选哪个订单"，
     * 不该在用户半小时后随口回一个数字时被重新翻出来。
     */
    @Test
    void expiredPendingShouldNotResume() {
        givenPending(pending(IntentType.ORDER_QUERY, LocalDateTime.now().minusHours(2)));
        ChatContext context = contextOf("1");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
        verify(conversationService).clearPendingClarification(SESSION);
    }

    /**
     * 没有挂起状态时直接走正常流程。
     */
    @Test
    void missingPendingShouldGoToIntent() {
        given(conversationService.getPendingClarification(anyString())).willReturn(null);
        ChatContext context = contextOf("1");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
        verify(conversationService, never()).clearPendingClarification(anyString());
    }

    /**
     * 读取挂起状态失败时降级为"没有挂起项"，而不是让整轮对话失败。
     */
    @Test
    void readFailureShouldDegradeToIntent() {
        given(conversationService.getPendingClarification(anyString()))
                .willThrow(new IllegalStateException("模拟数据库不可用"));
        ChatContext context = contextOf("1");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
    }

    /**
     * 挂起状态里的意图名解析不出来（历史数据、枚举改过名）时不硬续接：
     * 那会让工具层因"意图未登记工具"而失败，不如当作新问题重走一遍。
     */
    @Test
    void unresolvableIntentShouldNotResume() {
        PendingClarification broken = pending(IntentType.ORDER_QUERY, LocalDateTime.now());
        broken.setIntent("NO_SUCH_INTENT");
        givenPending(broken);
        ChatContext context = contextOf("1");

        assertThat(node.execute(context)).isEqualTo(ChatState.INTENT_RECOGNIZE);
        verify(conversationService).clearPendingClarification(SESSION);
    }

    /* ==================== 辅助 ==================== */

    /**
     * 让会话服务返回指定的挂起状态。
     *
     * @param pending 挂起状态
     */
    private void givenPending(PendingClarification pending) {
        given(conversationService.getPendingClarification(SESSION)).willReturn(pending);
    }

    /**
     * 构造挂起状态。
     *
     * @param intent    原意图
     * @param createdAt 挂起时间
     * @return 挂起状态
     */
    private static PendingClarification pending(IntentType intent, LocalDateTime createdAt) {
        return PendingClarification.builder()
                .question("我的订单到哪了")
                .intent(intent.name())
                .missingParam("orderNo")
                .createdAt(createdAt)
                .options(List.of(
                        PendingClarification.Option.builder()
                                .value("MC202409240001").label("MC202409240001 无线蓝牙耳机 Pro").build(),
                        PendingClarification.Option.builder()
                                .value("MC202409240002").label("MC202409240002 智能保温杯").build()))
                .build();
    }

    /**
     * 构造对话上下文。
     *
     * @param message 用户输入
     * @return 对话上下文
     */
    private static ChatContext contextOf(String message) {
        return ChatContext.builder()
                .sessionId(SESSION)
                .userMessage(message)
                .build();
    }
}
