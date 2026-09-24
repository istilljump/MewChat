package com.mewchat.tool.logistics;

import com.mewchat.tool.ToolResult;
import com.mewchat.tool.order.OrderTool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物流查询工具的单元测试（不依赖 Spring 与数据库）。
 *
 * <p>这个工具的核心设计是"运单号与订单号都能查"，因此测试的重点是
 * 两条入口各自可用、以及只给了订单号时能通过订单反查到物流。
 * 若只收运单号，"我的包裹到哪了"这类最常见的问法会因为用户拿不出运单号而无法回答。
 *
 * @author MewChat
 */
class LogisticsToolTest {

    private final LogisticsTool logisticsTool = new LogisticsTool(new OrderTool());

    /**
     * 运单号直查。
     */
    @Test
    void shouldQueryByTrackingNo() {
        ToolResult result = logisticsTool.invoke(Map.of("trackingNo", "SF1234567890"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary())
                .contains("运输中")
                .contains("顺丰速运")
                .contains("杭州西湖集散中心")
                .contains("2024-09-25 18:00:00");

        assertThat(result.getData())
                .containsEntry("status", "运输中")
                .containsEntry("carrier", "顺丰速运");
    }

    /**
     * 只给订单号也能查到物流 —— 用户手上通常只有订单号，运单号印在面单上。
     */
    @Test
    void shouldQueryByOrderNo() {
        ToolResult result = logisticsTool.invoke(Map.of("orderNo", "MC202409240001"));

        assertThat(result.isSuccess())
                .as("订单号应能反查出运单号进而查到物流")
                .isTrue();
        assertThat(result.getData()).containsEntry("trackingNo", "SF1234567890");
    }

    /**
     * 最新轨迹取的是时间上最新的那条，而不是列表里的最后一条。
     *
     * <p>轨迹按时间倒序存放，"最新"就是第一条；这条断言把该约定钉死 ——
     * 一旦有人往列表尾部追加新轨迹，就会出现"轨迹明细有，最新轨迹却是旧的"。
     */
    @Test
    void latestTraceShouldBeTheNewest() {
        ToolResult result = logisticsTool.invoke(Map.of("trackingNo", "SF1234567890"));

        assertThat(result.getData())
                .containsEntry("latestTraceDesc", "快件已到达【杭州西湖集散中心】");
        assertThat(String.valueOf(result.getData().get("latestTraceTime")))
                .startsWith("2024-09-23");
    }

    /**
     * 已签收的包裹没有预计送达时间，且摘要里要说明，不能让用户以为还在路上。
     */
    @Test
    void deliveredParcelShouldNotClaimEstimatedDelivery() {
        ToolResult result = logisticsTool.invoke(Map.of("trackingNo", "YT5566778899"));

        assertThat(result.getData()).containsEntry("status", "已签收");
        assertThat(result.getData().get("estimatedDeliveryTime")).isNull();
        assertThat(result.getSummary())
                .contains("已签收，无预计送达时间")
                .doesNotContain("预计送达时间：");
    }

    /**
     * 运单号与订单号都不给时应追问。
     */
    @Test
    void missingBothIdentifiersShouldAskForTrackingNo() {
        ToolResult result = logisticsTool.invoke(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getMissingParams()).containsExactly("trackingNo");
    }

    /**
     * 给了订单号但订单不存在时，缺的是"有效的订单号"，话术要指向核对订单号。
     */
    @Test
    void unknownOrderNoShouldAskToVerifyOrderNo() {
        ToolResult result = logisticsTool.invoke(Map.of("orderNo", "MC000000000000"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getClarifyHint()).contains("订单号").contains("MC000000000000");
    }

    /**
     * 订单存在但尚未发货时应说明"查不到物流"，而不是含糊地说系统故障。
     */
    @Test
    void orderNotShippedYetShouldExplainNoLogistics() {
        // MC202409240002 是待发货订单，其运单号 ZT9876543210 在物流表里是"待揽收"
        ToolResult result = logisticsTool.invoke(Map.of("orderNo", "MC202409240002"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("status", "待揽收");
    }

    /**
     * 运单号给错时应引导核对运单号。
     */
    @Test
    void unknownTrackingNoShouldAskToVerify() {
        ToolResult result = logisticsTool.invoke(Map.of("trackingNo", "SF0000000000"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getClarifyHint()).contains("运单").contains("SF0000000000");
        assertThat(result.getMissingParams())
                .as("用户给了运单号、只是值不对，不应报成'缺少参数'")
                .isEmpty();
    }

    /**
     * 模型把订单号填进了运单号键时也要能查到 —— 多试一个候选就能救回这类输入。
     */
    @Test
    void orderNoMistakenlyPlacedInTrackingNoShouldStillResolve() {
        ToolResult result = logisticsTool.invoke(Map.of("trackingNo", "MC202409240001"));

        assertThat(result.isSuccess())
                .as("运单号键里填了订单号时，应通过订单号反查救回")
                .isTrue();
        assertThat(result.getData()).containsEntry("trackingNo", "SF1234567890");
    }

    /**
     * 订单号与运单号都给、且都有效时，结果一致（不应因候选顺序不同而给出不同答案）。
     */
    @Test
    void bothIdentifiersShouldResolveToSameParcel() {
        ToolResult byTracking = logisticsTool.invoke(Map.of("trackingNo", "SF1234567890"));
        ToolResult byOrder = logisticsTool.invoke(Map.of("orderNo", "MC202409240001"));
        ToolResult byBoth = logisticsTool.invoke(
                Map.of("trackingNo", "SF1234567890", "orderNo", "MC202409240001"));

        assertThat(byBoth.getData()).isEqualTo(byTracking.getData());
        assertThat(byBoth.getData()).isEqualTo(byOrder.getData());
    }

    /**
     * {@code @Tool} 方法本身也应可直接调用（模型自主调用路径），且对空入参返回 null。
     */
    @Test
    void toolMethodShouldHandleBlankInput() {
        assertThat(logisticsTool.queryLogistics(null)).isNull();
        assertThat(logisticsTool.queryLogistics(new LogisticsTool.LogisticsQuery(null, "MC202409240001")))
                .isNull();
        assertThat(logisticsTool.queryLogistics(new LogisticsTool.LogisticsQuery("SF1234567890", null)))
                .isNotNull();
    }

    /**
     * 订单工具与物流工具的出参通过运单号对齐，两个工具的模拟数据不能各说各话。
     */
    @Test
    void orderAndLogisticsMockDataShouldAgree() {
        for (String orderNo : new String[]{"MC202409240001", "MC202409240002", "MC202409240003"}) {
            OrderTool.OrderInfo order = new OrderTool().queryOrder(new OrderTool.OrderQuery(orderNo));
            assertThat(order).as("订单 %s 应存在", orderNo).isNotNull();

            ToolResult logistics = logisticsTool.invoke(Map.of("orderNo", orderNo));
            assertThat(logistics.isSuccess())
                    .as("订单 %s 的运单号 %s 应能在物流表中查到", orderNo, order.trackingNo())
                    .isTrue();
            assertThat(logistics.getData()).containsEntry("trackingNo", order.trackingNo());
        }
    }

    /* ==================== 未发货：与"订单不存在"必须分开 ==================== */

    /**
     * 订单存在但尚未发货时，要说"还没发货"，不能报"查不到该订单，请核对订单号"。
     *
     * <p>两种成因给出同一句话的后果：用户会反复核对一个完全正确的订单号，
     * 而真正的原因（商家还没发货）始终没被告知 —— 这正是客服场景里最容易被投诉的一类回答。
     */
    @Test
    void notShippedOrderShouldSaySoInsteadOfNotFound() {
        // MC202409240004 是待发货订单，没有承运商也没有运单号
        ToolResult result = logisticsTool.invoke(Map.of("orderNo", "MC202409240004"));

        assertThat(result.isSuccess())
                .as("订单确实存在，这是一次成功的回答而不是'查不到'")
                .isTrue();
        assertThat(result.needsClarification())
                .as("不该反问用户核对号码 —— 号码没有错")
                .isFalse();
        assertThat(result.getSummary())
                .contains("MC202409240004")
                .contains("待发货")
                .contains("还未生成运单号");
        assertThat(result.getData())
                .containsEntry("orderNo", "MC202409240004")
                .containsEntry("shipped", false)
                .doesNotContainKey("trackingNo");
    }

    /**
     * 订单号确实不存在时，仍然要引导用户核对号码。
     *
     * <p>与上一条成对：区分两种成因不等于把两种都当成功。
     */
    @Test
    void missingOrderShouldStillAskToVerify() {
        ToolResult result = logisticsTool.invoke(Map.of("orderNo", "MC000000000000"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getClarifyHint()).contains("MC000000000000");
    }

    /**
     * 轨迹为 null 时摘要与结构化数据都不能崩。
     *
     * <p>同一个字段原先有两种假设：{@code latestTrace()} 判空，而摘要与 {@code toData()}
     * 直接遍历 —— 构造方一漏填就是 NPE。规范化放在记录的紧凑构造器里之后，
     * 两处假设合而为一。
     */
    @Test
    void nullTracesShouldNotBreakSummaryOrData() {
        LogisticsTool.LogisticsInfo info = new LogisticsTool.LogisticsInfo(
                "SF0000000000", "顺丰速运", "待揽收", null, null);

        assertThat(info.traces()).as("null 轨迹应被规范成空列表").isEmpty();
        assertThat(info.latestTrace()).isNull();
        assertThat(info.summary()).contains("暂无轨迹");
        assertThat(info.toData())
                .containsEntry("latestTraceTime", null)
                .containsEntry("latestTraceDesc", null);
        assertThat((java.util.List<?>) info.toData().get("traces")).isEmpty();
    }
}
