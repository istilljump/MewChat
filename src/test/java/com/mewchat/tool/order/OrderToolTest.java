package com.mewchat.tool.order;

import com.mewchat.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单查询工具的单元测试（不依赖 Spring 与数据库）。
 *
 * <p>重点覆盖三条分支：查到订单、没给单号、单号给错。
 * 后两条是客服场景里最常见的两种输入，也最容易实现成"返回失败" ——
 * 那种实现下用户会被告知"系统忙、建议转人工"，而实际上缺的只是核对一下单号。
 *
 * @author MewChat
 */
class OrderToolTest {

    private final OrderTool orderTool = new OrderTool();

    /**
     * 工具名必须与编排侧引用的常量一致，否则调用时报"未注册的工具"。
     */
    @Test
    void nameShouldBeStable() {
        assertThat(orderTool.name()).isEqualTo("order_query");
        assertThat(orderTool.name()).isEqualTo(OrderTool.NAME);
    }

    /**
     * 查到订单时，摘要与结构化数据都要齐全。
     */
    @Test
    void shouldReturnOrderDetail() {
        ToolResult result = orderTool.invoke(Map.of("orderNo", "MC202409240001"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolName()).isEqualTo(OrderTool.NAME);
        assertThat(result.needsClarification()).isFalse();

        // 摘要要带上模型组织话术所需的全部事实
        assertThat(result.getSummary())
                .contains("已发货")
                .contains("无线蓝牙耳机 Pro")
                .contains("499.00")
                .contains("2024-09-20 14:32:10")
                .contains("杭州市西湖区");

        assertThat(result.getData())
                .containsEntry("orderNo", "MC202409240001")
                .containsEntry("status", "已发货")
                .containsEntry("amount", new BigDecimal("499.00"))
                .containsEntry("trackingNo", "SF1234567890");
    }

    /**
     * 未发货的订单在摘要里不能出现 "null"，否则模型会把空值当事实讲给用户。
     */
    @Test
    void pendingOrderShouldNotLeakNullInSummary() {
        ToolResult result = orderTool.invoke(Map.of("orderNo", "MC202409240002"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("待发货").contains("中通快递").doesNotContain("null");
    }

    /**
     * 没给订单号时应走向追问，而不是当成查询失败。
     */
    @Test
    void missingOrderNoShouldAskForIt() {
        ToolResult result = orderTool.invoke(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getMissingParams()).containsExactly("orderNo");
    }

    /**
     * 参数表为 null 时也不能抛异常，同样走追问。
     */
    @Test
    void nullParamsShouldAskForOrderNo() {
        ToolResult result = orderTool.invoke(null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMissingParams()).containsExactly("orderNo");
    }

    /**
     * 订单号查不到时应让用户核对单号（追问），而不是报"查询失败"走兜底转人工。
     */
    @Test
    void unknownOrderNoShouldAskUserToVerify() {
        ToolResult result = orderTool.invoke(Map.of("orderNo", "MC000000000000"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification())
                .as("单号写错是输入问题，应引导用户核对，而不是当成系统答不上来")
                .isTrue();
        assertThat(result.getClarifyHint()).contains("MC000000000000");
        assertThat(result.getMissingParams())
                .as("用户给了订单号、只是值不对，不应报成'缺少参数'")
                .isEmpty();
    }

    /**
     * 大模型常把订单号当数字输出，取值时必须能接住非字符串类型。
     */
    @Test
    void numericOrderNoShouldBeAccepted() {
        Map<String, Object> params = new HashMap<>();
        params.put("orderNo", 202409240001L);

        ToolResult result = orderTool.invoke(params);

        // 这个号码不在模拟数据里，所以结果是"查不到"而不是"成功"。
        // 关键是它没有被当成"没给订单号"—— 那说明数字类型被接住了
        assertThat(result.getMissingParams())
                .as("数字类型的订单号不应被当成缺失参数")
                .isEmpty();
        assertThat(result.getClarifyHint()).contains("202409240001");
    }

    /**
     * 首尾空白必须被去掉，否则"带空格的单号查不到订单"极难从日志上发现。
     */
    @Test
    void orderNoWithWhitespaceShouldBeTrimmed() {
        ToolResult result = orderTool.invoke(Map.of("orderNo", "  MC202409240001  "));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("orderNo", "MC202409240001");
    }

    /**
     * 空白字符串等同于没给，应走追问。
     */
    @Test
    void blankOrderNoShouldBeTreatedAsMissing() {
        ToolResult result = orderTool.invoke(Map.of("orderNo", "   "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMissingParams()).containsExactly("orderNo");
    }

    /* ==================== 候选订单的归属过滤 ==================== */

    /**
     * 候选订单只能列出属于当前用户的。
     *
     * <p>这是本类唯一涉及数据越界的地方：列出别人的订单不是体验问题 ——
     * 订单号 + 商品名 + 状态足够拼出"某人买了什么"。
     * 因此三个方向都要钉死：本人看得到、别人一个也看不到、没有身份（游客）同样看不到。
     */
    @Test
    void candidatesShouldBeFilteredByOwner() {
        assertThat(orderTool.listOptions(OrderTool.DEMO_OWNER_USER_ID))
                .as("示例订单都归属演示用户，他自己应当看得到")
                .isNotEmpty();

        assertThat(orderTool.listOptions(OrderTool.DEMO_OWNER_USER_ID + 1))
                .as("别的用户不能看到他人的订单")
                .isEmpty();

        assertThat(orderTool.listOptions(null))
                .as("没有用户身份时不能返回任何订单")
                .isEmpty();
    }
}
