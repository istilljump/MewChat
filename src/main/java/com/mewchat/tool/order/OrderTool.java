package com.mewchat.tool.order;

import com.mewchat.dao.mysql.entity.Order;
import com.mewchat.service.BusinessDataService;
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
 * <p><b>数据来自 {@link BusinessDataService}</b>（表 {@code orders}，见 {@code sql/08}）：
 * 取数封装在 service 层是分层约定的要求，工具只负责"参数校验 → 取数 → 组织出参"。
 * 真实场景下订单数据来自交易系统，届时替换 service 实现的数据来源即可 ——
 * 上游只依赖 {@link BusinessTool} 契约，按工具名调用，不认识订单数据从哪来。
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
     * 演示/联调数据的归属用户ID。
     *
     * <p>与种子数据（{@code sql/09}）的 {@code owner_user_id} 以及演示账号
     * （{@code sql/06}，用户名 alice）一致。候选订单必须按归属过滤，
     * 把归属钥匙写进种子数据，"按用户过滤"这条路径才真的被走到、也才能被测试覆盖：
     * 换一个 userId 就得到空列表，而不是"看起来过滤了、实际返回全部"。
     */
    public static final long DEMO_OWNER_USER_ID = 1727138400000000001L;

    /** 数据访问入口。工具不做任何缓存：单条等值查询走唯一索引足够快，缓存反而引入一致性问题 */
    private final BusinessDataService businessDataService;

    public OrderTool(BusinessDataService businessDataService) {
        this.businessDataService = businessDataService;
    }

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
        Order order = businessDataService.findOrderByNo(query.orderNo().trim());
        return order == null ? null : fromEntity(order);
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
     * <p>过滤条件是硬性的：拿不到"属于当前用户"的订单就返回空列表 ——
     * 上层会退化成不带候选的"麻烦提供一下订单号"，功能少一截，但不会泄露任何数据。
     * <b>宁可少列，也不能多列。</b>种子数据把示例订单都挂在
     * {@link #DEMO_OWNER_USER_ID} 名下，于是过滤是真的在生效
     * （换一个 userId 就得到空列表）。真实接入后取数换成订单系统按归属查询，
     * 绝不允许退化成"查不到归属就返回全部"。
     *
     * 澄清候选填的参数名：候选值是订单号，用户回复序号后由续接逻辑填进 orderNo。
     */
    @Override
    public String clarificationParam() {
        return "orderNo";
    }

    /**
     * 列出可选的订单，供"缺订单号"时的澄清追问使用。
     *
     * <p><b>必须按 userId 过滤。</b> 列出别人的订单不是体验问题，而是泄露他人的交易信息
     * （订单号 + 商品 + 状态足够拼出"某人买了什么"）。
     *
     * <p>过滤条件是硬性的：拿不到"属于当前用户"的订单就返回空列表 ——
     * 上层会退化成不带候选的"麻烦提供一下订单号"，功能少一截，但不会泄露任何数据。
     * <b>宁可少列，也不能多列。</b>种子数据把示例订单都挂在
     * {@link #DEMO_OWNER_USER_ID} 名下，于是过滤是真的在生效
     * （换一个 userId 就得到空列表）。真实接入后取数换成订单系统按归属查询，
     * 绝不允许退化成"查不到归属就返回全部"。
     *
     * @param userId 当前登录用户ID；为空（游客）时返回空列表
     * @return 候选项列表，只含属于该用户的订单
     */
    @Override
    public List<ClarificationOption> listOptions(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return businessDataService.listOrdersByOwner(userId).stream()
                .map(OrderTool::fromEntity)
                .map(order -> new ClarificationOption(
                        order.orderNo(),
                        order.orderNo() + " " + order.productName() + "（" + order.status() + "）"))
                .toList();
    }

    /**
     * 数据库实体 → 工具出参。
     *
     * <p>转换放在工具层而不是 service：{@link OrderInfo} 是"给模型与接口层看的表达"，
     * 属于工具的契约；service 返回的是"存储的表达"。两边各管各的，
     * 改出参字段不会牵动 service 的查询方法。
     *
     * @param order 数据库订单实体
     * @return 工具出参对象
     */
    private static OrderInfo fromEntity(Order order) {
        return new OrderInfo(
                order.getOrderNo(),
                order.getOwnerUserId(),
                order.getStatus(),
                order.getProductName(),
                order.getQuantity(),
                order.getAmount(),
                order.getOrderTime(),
                order.getReceiverAddress(),
                order.getReceiverName(),
                order.getReceiverPhone(),
                order.getCarrier(),
                order.getTrackingNo());
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
