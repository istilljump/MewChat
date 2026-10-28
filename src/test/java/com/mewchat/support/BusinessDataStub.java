package com.mewchat.support;

import com.mewchat.dao.mysql.entity.Logistics;
import com.mewchat.dao.mysql.entity.Order;
import com.mewchat.dao.mysql.entity.Product;
import com.mewchat.dao.mysql.entity.RefundPolicy;
import com.mewchat.service.BusinessDataService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 业务数据服务的内存桩：数据值与种子脚本（{@code sql/09_business_seed.sql}）
 * 及迁移前的进程内模拟数据逐字一致。
 *
 * <p><b>为什么数据值必须与种子一致</b>：工具单测断言的是这些具体值
 * （499.00、SF1234567890、"已发货"、在售过滤），与真库种子保持同一份值，
 * "单测绿的"与"真库跑通的"才是同一个行为 —— 两份各写各的值，
 * 单测对不上的那个分支在真库里一样是错的。
 *
 * <p>归属：全部订单挂在演示账号 alice（{@code OrderTool.DEMO_OWNER_USER_ID}）名下，
 * "按用户过滤候选"这条路径在单测里也真的被走到。
 *
 * @author MewChat
 */
public final class BusinessDataStub implements BusinessDataService {

    private final List<Order> orders;
    private final List<Logistics> logistics;
    private final List<Product> products;
    private final List<RefundPolicy> policies;

    private BusinessDataStub(List<Order> orders,
                             List<Logistics> logistics,
                             List<Product> products,
                             List<RefundPolicy> policies) {
        this.orders = List.copyOf(orders);
        this.logistics = List.copyOf(logistics);
        this.products = List.copyOf(products);
        this.policies = List.copyOf(policies);
    }

    /**
     * 构造与种子数据一致的桩。
     *
     * @return 新的桩实例（测试间互不共享可变状态，尽管本桩只读）
     */
    public static BusinessDataStub of() {
        long owner = OrderToolHolder.DEMO_OWNER;

        List<Order> orders = List.of(
                order(1810000000000000001L, "MC202409240001", owner, "已发货",
                        "无线蓝牙耳机 Pro", 1, "499.00",
                        LocalDateTime.of(2024, 9, 20, 14, 32, 10),
                        "浙江省杭州市西湖区文三路 100 号 1 幢 201 室", "张*", "138****8888",
                        "顺丰速运", "SF1234567890"),
                order(1810000000000000002L, "MC202409240002", owner, "待发货",
                        "智能保温杯 500ml", 2, "258.00",
                        LocalDateTime.of(2024, 9, 22, 9, 15, 33),
                        "江苏省南京市鼓楼区中山北路 8 号 3 单元 602", "李*", "139****6666",
                        "中通快递", "ZT9876543210"),
                order(1810000000000000003L, "MC202409240003", owner, "已完成",
                        "机械键盘 87 键", 1, "399.00",
                        LocalDateTime.of(2024, 9, 10, 20, 5, 47),
                        "广东省深圳市南山区科技园路 5 号 A 座 1203", "王*", "137****1234",
                        "圆通速递", "YT5566778899"),
                // 尚未发货：没有承运商与运单号，是"订单查得到、换不出运单号"分支唯一的入口
                order(1810000000000000004L, "MC202409240004", owner, "待发货",
                        "便携充电宝 10000mAh", 1, "129.00",
                        LocalDateTime.of(2024, 9, 23, 18, 40, 5),
                        "北京市朝阳区建国路 88 号 5 号楼 801", "赵*", "136****5678",
                        null, null));

        List<Logistics> logistics = List.of(
                logistics(1830000000000000001L, "SF1234567890", "顺丰速运", "运输中",
                        LocalDateTime.of(2024, 9, 25, 18, 0),
                        trace("2024-09-23 08:15", "快件已到达【杭州西湖集散中心】"),
                        trace("2024-09-22 21:40", "快件已从【南京转运中心】发出，下一站【杭州西湖集散中心】"),
                        trace("2024-09-22 15:20", "顺丰速运已收取快件")),
                logistics(1830000000000000002L, "ZT9876543210", "中通快递", "待揽收",
                        LocalDateTime.of(2024, 9, 26, 18, 0),
                        trace("2024-09-22 09:40", "商家已打印运单，等待快递员上门揽收")),
                logistics(1830000000000000003L, "YT5566778899", "圆通速递", "已签收", null,
                        trace("2024-09-13 16:22", "快件已签收，签收人：本人"),
                        trace("2024-09-13 09:05", "快件正在派送中，派送员正在为您派送"),
                        trace("2024-09-12 19:30", "快件已到达【深圳南山网点】"),
                        trace("2024-09-11 10:12", "圆通速递已收取快件")));

        List<Product> products = List.of(
                product(1820000000000000001L, "无线蓝牙耳机 Pro", "数码配件", "499.00",
                        "蓝牙 5.3 / 主动降噪 / 单次续航 8 小时（含仓 30 小时）", 128, true),
                product(1820000000000000002L, "机械键盘 87 键", "数码配件", "399.00",
                        "87 键 / 茶轴 / 有线 + 2.4G 双模 / PBT 键帽", 46, true),
                // 缺货且下架：让"还有货吗"能答出否定结果，也让"候选只列在售"有东西可过滤
                product(1820000000000000003L, "便携充电宝 10000mAh", "数码配件", "129.00",
                        "10000mAh / 22.5W 快充 / 支持双向充电 / 可登机", 0, false),
                product(1820000000000000004L, "智能保温杯 500ml", "家具家电", "258.00",
                        "500ml / 316 不锈钢内胆 / 触控显温 / 保温 12 小时", 75, true),
                product(1820000000000000005L, "纯棉圆领 T 恤", "服饰鞋包", "89.00",
                        "100% 精梳棉 / 男女同款 / S-XXL / 5 色可选", 312, true),
                product(1820000000000000006L, "云南普洱熟茶 357g", "食品生鲜", "168.00",
                        "357g 七子饼 / 五年陈 / 独立包装", 58, true));

        List<RefundPolicy> policies = List.of(
                policy(1840000000000000001L, "数码配件", 7, 15,
                        "人为损坏、进液、私自拆机不在退换范围内。",
                        "支持 7 天无理由退货，商品需保持外观完好、配件与包装齐全",
                        "已激活或已拆封的手机、平板等设备不支持无理由退货",
                        "因质量问题退换货，往返运费由平台承担"),
                policy(1840000000000000002L, "服饰鞋包", 7, 15,
                        "尺码不合属于无理由退货范围，可在订单页直接申请。",
                        "支持 7 天无理由退货，吊牌需完整、未洗涤未穿着",
                        "内衣、袜子等贴身类商品因卫生要求不支持无理由退货",
                        "换货运费：非质量问题时由买家承担"),
                policy(1840000000000000003L, "美妆个护", 7, 7,
                        "开封后出现过敏等不适，请保留就医凭证联系客服处理。",
                        "未拆封商品支持 7 天无理由退货",
                        "已拆封的化妆品、洗护用品因卫生要求不支持无理由退货",
                        "商品与描述不符、破损、临期等问题可全额退款"),
                policy(1840000000000000004L, "食品生鲜", 0, 0,
                        "生鲜商品请尽量当面签收验货，签收后超过 24 小时不再受理质量异议。",
                        "生鲜类商品不支持 7 天无理由退货",
                        "签收后 24 小时内发现变质、缺斤少两可申请质量问题退款",
                        "需提供商品与面单合照作为凭证"),
                policy(1840000000000000005L, "家具家电", 7, 30,
                        "涉及上门安装的商品，请勿自行拆箱，以免影响退换。",
                        "支持 7 天无理由退货，但需未安装、未使用且包装完好",
                        "已安装的大家电退换需工程师上门检测后判定",
                        "大件商品退换由平台安排上门取件，非质量问题运费由买家承担"),
                policy(1840000000000000006L, "通用", 7, 15,
                        "不确定商品是否支持无理由退货时，可直接提供订单号由客服核实。",
                        "支持 7 天无理由退货，自签收之日起算，商品需不影响二次销售",
                        "定制类商品、已拆封的数字化商品、个人护理用品不支持无理由退货",
                        "因质量问题产生的退换货运费由平台承担"));

        return new BusinessDataStub(orders, logistics, products, policies);
    }

    /* ==================== 测试取数辅助 ==================== */

    /**
     * 全部订单（按录入顺序）。
     *
     * @return 订单列表
     */
    public List<Order> orders() {
        return orders;
    }

    /**
     * 全部商品（按录入顺序），供测试遍历做跨工具断言（如类目一致性）。
     *
     * @return 商品列表
     */
    public List<Product> products() {
        return products;
    }

    /**
     * 全部退款政策（按录入顺序）。
     *
     * @return 政策列表
     */
    public List<RefundPolicy> policies() {
        return policies;
    }

    /* ==================== BusinessDataService ==================== */

    @Override
    public Order findOrderByNo(String orderNo) {
        return byKey(orders, Order::getOrderNo, orderNo);
    }

    @Override
    public List<Order> listOrdersByOwner(Long ownerUserId) {
        if (ownerUserId == null) {
            return List.of();
        }
        // 不排序（保持录入顺序）：与实现类的 orderByAsc(id) 对齐，
        // 候选列表顺序是对外可见的行为，两边的顺序必须一致
        return orders.stream()
                .filter(order -> ownerUserId.equals(order.getOwnerUserId()))
                .toList();
    }

    @Override
    public Logistics findLogisticsByTrackingNo(String trackingNo) {
        return byKey(logistics, Logistics::getTrackingNo, trackingNo);
    }

    @Override
    public Product findProductByName(String productName) {
        return byKey(products, Product::getProductName, productName);
    }

    @Override
    public List<Product> listAllProducts() {
        return products;
    }

    @Override
    public List<RefundPolicy> listAllRefundPolicies() {
        return policies;
    }

    private static <T> T byKey(List<T> list, Function<T, String> key, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Map<String, T> map = list.stream()
                .collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
        return map.get(value.trim());
    }

    /* ==================== 构造辅助 ==================== */

    private static Order order(long id, String orderNo, long ownerUserId, String status,
                               String productName, int quantity, String amount,
                               LocalDateTime orderTime, String address, String name, String phone,
                               String carrier, String trackingNo) {
        return Order.builder()
                .id(id).orderNo(orderNo).ownerUserId(ownerUserId).status(status)
                .productName(productName).quantity(quantity)
                .amount(new BigDecimal(amount)).orderTime(orderTime)
                .receiverAddress(address).receiverName(name).receiverPhone(phone)
                .carrier(carrier).trackingNo(trackingNo).deleted(0)
                .build();
    }

    private static Logistics logistics(long id, String trackingNo, String carrier, String status,
                                       LocalDateTime eta, Logistics.LogisticsTrace... traces) {
        return Logistics.builder()
                .id(id).trackingNo(trackingNo).carrier(carrier).status(status)
                .estimatedDeliveryTime(eta)
                .traces(new java.util.ArrayList<>(List.of(traces)))
                .deleted(0)
                .build();
    }

    private static Logistics.LogisticsTrace trace(String time, String description) {
        return new Logistics.LogisticsTrace(LocalDateTime.parse(time.replace(' ', 'T')), description);
    }

    private static Product product(long id, String name, String category, String price,
                                   String spec, int stock, boolean onSale) {
        return Product.builder()
                .id(id).productName(name).category(category)
                .price(new BigDecimal(price)).spec(spec).stock(stock).onSale(onSale)
                .deleted(0)
                .build();
    }

    private static RefundPolicy policy(long id, String category, int returnDays, int exchangeDays,
                                       String note, String... rules) {
        return RefundPolicy.builder()
                .id(id).category(category)
                .returnWindowDays(returnDays).exchangeWindowDays(exchangeDays)
                .rules(new java.util.ArrayList<>(List.of(rules)))
                .note(note).deleted(0)
                .build();
    }

    /**
     * 演示归属ID的持有器：避免本类直接依赖 {@code OrderTool}（工具层），
     * 该值与 {@code OrderTool.DEMO_OWNER_USER_ID} 与种子脚本保持一致。
     */
    private static final class OrderToolHolder {
        private static final long DEMO_OWNER = 1727138400000000001L;
    }
}
