package com.mewchat.tool;

import com.mewchat.dao.mysql.entity.Order;
import com.mewchat.service.BusinessDataService;
import com.mewchat.tool.order.OrderTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务数据落库后的端到端验证（需要真实 MySQL，且已执行 sql/08 + sql/09）。
 *
 * <p>阶段 27 把四个工具的进程内模拟数据迁到 MySQL 表。这次迁移的关键风险
 * 与阶段 11 的 JSON 列同族：<b>JSON 列（traces / rules）的类型往返</b>、
 * <b>种子数据与评测/演示依赖的具体值是否逐字一致</b>、以及
 * <b>工具真的从库里取数</b>（而不是另一份残留的内存数据在"看起来正常"）。
 * 这些都是 Mock 测不到的 —— Mock 的 Mapper 不做 JSON 序列化，
 * 也正因为如此，本测试只读不写、跑完零残留。
 *
 * <p>默认不执行，需显式开启（账号密码必须显式传入，应用账号没有 root 权限）：
 * <pre>
 * ./mvnw test -Dmewchat.it.mysql=true \
 *     -Dmewchat.it.mysql.username=mewchat -Dmewchat.it.mysql.password=***
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
class MysqlBusinessDataIntegrationTest {

    @Autowired
    private BusinessDataService businessDataService;

    @Autowired
    private ToolInvoker toolInvoker;

    /**
     * 种子订单可查、归属过滤真实生效。
     */
    @Test
    void seedOrdersShouldBeQueryableWithOwnerFilter() {
        Order order = businessDataService.findOrderByNo("MC202409240001");

        assertThat(order).as("种子订单应存在（sql/09）").isNotNull();
        assertThat(order.getStatus()).isEqualTo("已发货");
        assertThat(order.getTrackingNo()).isEqualTo("SF1234567890");
        assertThat(order.getAmount()).isEqualByComparingTo(new BigDecimal("499.00"));
        assertThat(order.getOwnerUserId()).isEqualTo(OrderTool.DEMO_OWNER_USER_ID);

        // 归属过滤是安全红线：本人 4 条、他人 0 条、游客 0 条
        List<Order> own = businessDataService.listOrdersByOwner(OrderTool.DEMO_OWNER_USER_ID);
        assertThat(own).hasSize(4);
        assertThat(own.get(0).getOrderNo())
                .as("候选顺序必须稳定（评测与演示依赖'第 1 项'）")
                .isEqualTo("MC202409240001");
        assertThat(businessDataService.listOrdersByOwner(OrderTool.DEMO_OWNER_USER_ID + 1)).isEmpty();
        assertThat(businessDataService.listOrdersByOwner(null)).isEmpty();
    }

    /**
     * JSON 列在真实 MySQL 上往返：轨迹列表与政策要点必须是解析后的对象，不是 JSON 文本。
     *
     * <p>这正是阶段 11 踩过的坑（JacksonTypeHandler + autoResultMap）——
     * 没配对时查回来是一串未解析的 JSON 文本，不报错、表现为"轨迹永远为空"。
     */
    @Test
    void jsonColumnsShouldRoundTripThroughRealMysql() {
        var logistics = businessDataService.findLogisticsByTrackingNo("SF1234567890");
        assertThat(logistics).isNotNull();
        assertThat(logistics.getTraces())
                .as("traces 必须被解析成轨迹对象列表")
                .isNotEmpty();
        assertThat(logistics.getTraces().get(0).getDescription())
                .as("轨迹倒序存放，第一条即最新（2024-09-23）")
                .contains("杭州西湖集散中心");
        assertThat(logistics.getTraces().get(0).getTime())
                .isEqualTo(java.time.LocalDateTime.of(2024, 9, 23, 8, 15));

        var policy = businessDataService.listAllRefundPolicies().stream()
                .filter(p -> "数码配件".equals(p.getCategory()))
                .findFirst().orElseThrow();
        assertThat(policy.getRules())
                .as("rules 必须被解析成要点列表")
                .hasSize(3);
    }

    /**
     * 工具经注册表调用，数据真的来自数据库。
     *
     * <p>断言的都是种子里的具体值：这些值同时也是评测集（tools/eval/cases.json）
     * 的断言依据 —— 这里对得上，评测才有意义。
     */
    @Test
    void toolsShouldReadFromDatabase() {
        // 订单：值来自库
        ToolResult order = toolInvoker.invoke(OrderTool.NAME, Map.of("orderNo", "MC202409240001"));
        assertThat(order.isSuccess()).isTrue();
        assertThat(order.getSummary()).contains("已发货").contains("499.00").contains("SF1234567890");

        // 物流：订单号反查运单号（跨工具依赖走 service）
        ToolResult logistics = toolInvoker.invoke("logistics_query", Map.of("orderNo", "MC202409240001"));
        assertThat(logistics.isSuccess()).isTrue();
        assertThat(logistics.getData()).containsEntry("trackingNo", "SF1234567890");

        // 未发货订单：说"还未生成运单号"，不报"查不到"
        ToolResult pending = toolInvoker.invoke("logistics_query", Map.of("orderNo", "MC202409240004"));
        assertThat(pending.isSuccess()).isTrue();
        assertThat(pending.getSummary()).contains("还未生成运单号");

        // 商品：关键词命中 + 价格来自库
        ToolResult product = toolInvoker.invoke("product_query", Map.of("productName", "耳机"));
        assertThat(product.isSuccess()).isTrue();
        assertThat(product.getData()).containsEntry("productName", "无线蓝牙耳机 Pro");
        assertThat((BigDecimal) product.getData().get("price"))
                .isEqualByComparingTo(new BigDecimal("499.00"));

        // 候选只列在售商品（缺货的充电宝被排除）
        List<ClarificationOption> options = toolInvoker.listOptions("product_query", null);
        assertThat(options).isNotEmpty();
        assertThat(options).noneSatisfy(option ->
                assertThat(option.value()).isEqualTo("便携充电宝 10000mAh"));

        // 退款政策：生鲜 0 天 = 不支持无理由退货
        ToolResult refund = toolInvoker.invoke("refund_policy_query", Map.of("category", "生鲜"));
        assertThat(refund.isSuccess()).isTrue();
        assertThat(refund.getData())
                .containsEntry("category", "食品生鲜")
                .containsEntry("returnWindowDays", 0);

        // 查不到：仍是"请核对"而不是失败
        ToolResult unknown = toolInvoker.invoke(OrderTool.NAME, Map.of("orderNo", "MC000000000000"));
        assertThat(unknown.needsClarification()).isTrue();
    }

    /**
     * 跨表参照完整性：每个商品的类目在真实政策表里都有专属条目。
     *
     * <p>单测里两份数据来自同一个桩、天然一致；真库上两表独立，
     * 只有这里能发现"商品表加了新类目、政策表没跟上"的分叉。
     */
    @Test
    void productCategoriesShouldExistInRefundPolicyTable() {
        for (var product : businessDataService.listAllProducts()) {
            var matched = businessDataService.listAllRefundPolicies().stream()
                    .filter(p -> product.getCategory().equals(p.getCategory()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "商品「" + product.getProductName() + "」的类目「" + product.getCategory()
                                    + "」在退款政策表里没有专属条目（两表数据已分叉）"));
            assertThat(matched.getCategory()).isEqualTo(product.getCategory());
        }
    }
}
