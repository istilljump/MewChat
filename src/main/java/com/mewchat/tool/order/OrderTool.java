package com.mewchat.tool.order;

import com.mewchat.tool.BusinessTool;
import com.mewchat.tool.ClarificationOption;
import com.mewchat.tool.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单查询工具：按订单号查出订单状态与商品、金额、下单时间、收货地址。
 *
 * <p><b>为什么入参是一个对象而不是一个 String</b>：工具的参数结构最终要暴露给大模型
 * （见 {@link #queryOrder} 上的 {@code @Tool}），对象化的结构才能生成带字段名与说明的
 * JSON Schema，模型才知道"订单号"该往哪个键里填。将来订单查询要加"下单手机号核验"，
 * 只需给 {@link OrderQuery} 加一个字段，调用方与编排层都不用改。
 *
 * <p><b>大模型只负责提取参数</b>：订单状态、金额这些业务数据一律由本工具查出来。
 * 让模型"顺手"生成业务数据是这类系统里最危险的写法 —— 它会用看起来完全合理的
 * 数字和状态编造一个不存在的订单，而用户无从分辨。
 *
 * <p><b>当前是进程内模拟数据</b>（{@link #MOCK_ORDERS}）：本机数据库里没有订单表，
 * 真实场景下订单数据来自交易系统的接口。接入时替换 {@link #queryOrder} 的取数实现即可 ——
 * 上游只依赖 {@link BusinessTool} 契约，按工具名调用，不认识订单数据从哪来。
 * 届时按分层约定把取数封装到 {@code service} 层，工具类改为注入该服务。
 *
 * @author MewChat
 */
@Component
public class OrderTool implements BusinessTool {

    /**
     * 工具名。
     *
     * <p>注解与本常量引用的是同一个值，因此"编排侧按 {@code order_query} 调用、
     * 注解里却写着别的名字"这种错配不可能发生。
     */
    public static final String NAME = "order_query";

    private static final Logger log = LoggerFactory.getLogger(OrderTool.class);

    /** 摘要里的时间格式：摘要给模型读，可读性优先于机器解析 */
    private static final DateTimeFormatter SUMMARY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 模拟订单的归属用户ID（演示用）。
     *
     * <p>模拟数据必须带归属信息，否则 {@link #listOptions(Long)} 的过滤无从生效 ——
     * 一个"有过滤但永远匹配不上"的实现，和一个"根本没过滤"的实现，
     * 在有真实数据后会表现出完全不同的行为：前者继续保持安全，后者立刻开始泄露。
     * 把归属写进数据里，过滤这条路径才真的被走到、也才能被测试覆盖。
     *
     * <p>取值与测试里用的用户ID一致，本地联调时用该身份登录就能看到候选订单列表。
     */
    public static final long DEMO_OWNER_USER_ID = 1727138400000000001L;

    /**
     * 模拟订单数据，键为订单号。
     *
     * <p>收件人姓名与手机号在数据里<b>本身就是脱敏的</b>：客服场景不需要完整的
     * 个人信息，更不该把它送进模型提示词或接口响应 —— 一旦进了提示词，
     * 就等于把用户隐私交给了第三方模型服务商。
     */
    private static final Map<String, OrderInfo> MOCK_ORDERS = buildMockOrders();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResult invoke(Map<String, Object> params) {
        OrderQuery query = OrderQuery.from(params);
        if (!StringUtils.hasText(query.orderNo())) {
            // 没给订单号：走追问。这不是错误，是最常见的一轮对话
            return ToolResult.needMoreInfo(NAME, List.of("orderNo"));
        }

        OrderInfo order = queryOrder(query);
        if (order == null) {
            log.debug("订单不存在：orderNo={}", query.orderNo());
            // 单号写错/记错是最常见的原因，让用户核对远好于报"查询失败"
            return ToolResult.notFound(NAME,
                    "抱歉，没有查到订单号「" + query.orderNo() + "」对应的订单，"
                            + "麻烦您核对一下订单号是否正确。");
        }

        log.debug("订单查询成功：orderNo={} status={}", order.orderNo(), order.status());
        return ToolResult.ok(NAME, order.summary(), order.toData());
    }

    /**
     * 按订单号查询订单详情。
     *
     * <p>本方法同时被两条路径使用：编排层的确定性调用（{@link #invoke}），
     * 以及将来模型自主选择工具时的调用（靠 {@code @Tool} 生成的工具描述）。
     *
     * @param query 查询条件
     * @return 订单详情；订单号为空或查不到时返回 null
     */
    @Tool(name = NAME, value = "根据订单号查询订单详情，返回订单状态、商品名称、实付金额、下单时间与收货地址")
    public OrderInfo queryOrder(@P("订单查询条件，订单号为必填项") OrderQuery query) {
        if (query == null || !StringUtils.hasText(query.orderNo())) {
            return null;
        }
        return MOCK_ORDERS.get(query.orderNo().trim());
    }

    /**
     * 列出可选的订单，供"缺订单号"时的澄清追问使用。
     *
     * <p><b>必须按 userId 过滤。</b> 列出别人的订单不是体验问题，而是泄露他人的交易信息
     * （订单号 + 商品 + 状态足够拼出"某人买了什么"）。因此过滤条件是硬性的：
     * 拿不到"属于当前用户"的订单就返回空列表 —— 上层会退化成不带候选的
     * "麻烦提供一下订单号"，功能少一截，但不会泄露任何数据。
     * <b>宁可少列，也不能多列。</b>
     *
     * <p>模拟数据把三条示例订单都挂在 {@link #DEMO_OWNER_USER_ID} 名下，
     * 于是过滤是真的在生效（换一个 userId 就得到空列表），
     * 而不是"看起来过滤了、实际返回全部"。真实实现应从订单系统按归属查询，
     * 绝不允许退化成"查不到归属就返回全部"。
     *
     * @param userId 当前登录用户ID；为空（游客）时返回空列表
     * @return 候选项列表，只含属于该用户的订单
     */
    @Override
    public String clarificationParam() {
        return "orderNo";
    }

    @Override
    public List<ClarificationOption> listOptions(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return MOCK_ORDERS.values().stream()
                .filter(order -> userId.equals(order.ownerUserId()))
                .map(order -> new ClarificationOption(
                        order.orderNo(),
                        order.orderNo() + " " + order.productName() + "（" + order.status() + "）"))
                .toList();
    }

    /**
     * 构造模拟订单数据。
     *
     * <p>订单号只覆盖后面几个业务场景要用的样例：一个在途、一个未发货、一个已签收，
     * 这正好对应物流查询的三种典型回答。刻意不造"万能订单"，
     * 否则"查不到单"这条分支永远测不到。
     *
     * @return 不可变的订单表
     */
    private static Map<String, OrderInfo> buildMockOrders() {
        Map<String, OrderInfo> orders = new LinkedHashMap<>();

        orders.put("MC202409240001", new OrderInfo(
                "MC202409240001", DEMO_OWNER_USER_ID, "已发货", "无线蓝牙耳机 Pro", 1,
                new BigDecimal("499.00"), LocalDateTime.of(2024, 9, 20, 14, 32, 10),
                "浙江省杭州市西湖区文三路 100 号 1 幢 201 室", "张*", "138****8888",
                "顺丰速运", "SF1234567890"));

        orders.put("MC202409240002", new OrderInfo(
                "MC202409240002", DEMO_OWNER_USER_ID, "待发货", "智能保温杯 500ml", 2,
                new BigDecimal("258.00"), LocalDateTime.of(2024, 9, 22, 9, 15, 33),
                "江苏省南京市鼓楼区中山北路 8 号 3 单元 602", "李*", "139****6666",
                "中通快递", "ZT9876543210"));

        orders.put("MC202409240003", new OrderInfo(
                "MC202409240003", DEMO_OWNER_USER_ID, "已完成", "机械键盘 87 键", 1,
                new BigDecimal("399.00"), LocalDateTime.of(2024, 9, 10, 20, 5, 47),
                "广东省深圳市南山区科技园路 5 号 A 座 1203", "王*", "137****1234",
                "圆通速递", "YT5566778899"));

        // 尚未发货：没有承运商、也没有运单号。
        // 这条数据是"订单查得到、但换不出运单号"那条分支唯一的入口 ——
        // 缺了它，物流工具里"订单存在但未发货"的话术在真实数据接入前永远不会被执行到，
        // 也就等于没被验证过
        orders.put("MC202409240004", new OrderInfo(
                "MC202409240004", DEMO_OWNER_USER_ID, "待发货", "便携充电宝 10000mAh", 1,
                new BigDecimal("129.00"), LocalDateTime.of(2024, 9, 23, 18, 40, 5),
                "北京市朝阳区建国路 88 号 5 号楼 801", "赵*", "136****5678",
                null, null));

        return Collections.unmodifiableMap(orders);
    }

    /* ==================== 入参 / 出参 ==================== */

    /**
     * 订单查询入参。
     *
     * @param orderNo 订单号，必填
     */
    public record OrderQuery(String orderNo) {

        /**
         * 从参数表绑定入参。
         *
         * <p>绑定逻辑写在入参自己身上，是为了让"模型给了什么键"与"工具要什么字段"
         * 这对关系紧挨在一起 —— 新增字段时在一个地方改完，
         * 不会出现"字段加了但绑定忘了写"的值永远是 null 的问题。
         *
         * @param params 参数表，允许为 null
         * @return 入参对象，缺字段时为 null 字段
         */
        public static OrderQuery from(Map<String, Object> params) {
            // 限定接口名调用：接口的静态方法不会被实现类继承，
            // 这里的 from() 又是记录自己的静态方法，不限定就找不到
            return new OrderQuery(BusinessTool.stringParam(params, "orderNo"));
        }
    }

    /**
     * 订单详情出参。
     *
     * <p>同时服务于两个下游：{@link #summary()} 供大模型组织话术，
     * {@link #toData()} 供接口层直接渲染订单卡片。
     * 一份数据两种表达，避免让模型去解析 JSON 结构、也避免前端去解析自然语言。
     *
     * @param orderNo         订单号
     * @param ownerUserId     归属用户ID。澄清追问按它过滤候选，见 {@link #listOptions(Long)}
     * @param status          订单状态（已发货 / 待发货 / 已完成……）
     * @param productName     商品名称
     * @param quantity        购买数量
     * @param amount          实付金额
     * @param orderTime       下单时间
     * @param receiverAddress 收货地址
     * @param receiverName    收件人姓名（已脱敏）
     * @param receiverPhone   收件人手机号（已脱敏）
     * @param carrier         承运商，未发货时可能为空
     * @param trackingNo      运单号，未发货时可能为空
     */
    public record OrderInfo(String orderNo,
                           Long ownerUserId,
                           String status,
                           String productName,
                           Integer quantity,
                           BigDecimal amount,
                           LocalDateTime orderTime,
                           String receiverAddress,
                           String receiverName,
                           String receiverPhone,
                           String carrier,
                           String trackingNo) {

        /**
         * 生成给大模型看的自然语言摘要。
         *
         * <p>已经查到的订单数据本身就是事实，把事实整理成短句交给模型，
         * 它只需组织语气、不需要"理解"结构，也就少了一处可能理解错的地方。
         *
         * @return 摘要文本
         */
        public String summary() {
            return "订单号 " + orderNo + "，订单状态：" + status
                    + "（下单时间 " + formatTime(orderTime) + "）\n"
                    + "商品：" + productName + " × " + quantity + "\n"
                    + "实付金额：￥" + amount + "\n"
                    + "收货地址：" + receiverAddress
                    + "（收件人 " + receiverName + "，手机 " + receiverPhone + "）\n"
                    + "物流：" + nullToUnknown(carrier) + " " + nullToUnknown(trackingNo);
        }

        /**
         * 生成给接口层的结构化数据。
         *
         * @return 字段名到取值的映射
         */
        public Map<String, Object> toData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderNo", orderNo);
            data.put("status", status);
            data.put("productName", productName);
            data.put("quantity", quantity);
            data.put("amount", amount);
            data.put("orderTime", orderTime);
            data.put("receiverAddress", receiverAddress);
            data.put("receiverName", receiverName);
            data.put("receiverPhone", receiverPhone);
            data.put("carrier", carrier);
            data.put("trackingNo", trackingNo);
            return data;
        }

        /**
         * 格式化时间。
         *
         * @param time 时间，可为 null（待发货订单没有发货时间等场景）
         * @return 格式化文本
         */
        private static String formatTime(LocalDateTime time) {
            return time == null ? "未知" : SUMMARY_TIME_FORMAT.format(time);
        }

        /**
         * 空值转"待出库"这类可读文本。
         *
         * <p>摘要里写"null null"会让模型把空值当成一个事实照抄给用户，
         * 未发货的订单应该老实说还没发货。
         *
         * @param value 原始值
         * @return 去空后的文本
         */
        private static String nullToUnknown(String value) {
            return StringUtils.hasText(value) ? value : "暂无";
        }
    }
}
