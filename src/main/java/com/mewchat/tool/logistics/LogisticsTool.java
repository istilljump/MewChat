package com.mewchat.tool.logistics;

import com.mewchat.tool.BusinessTool;
import com.mewchat.tool.ClarificationOption;
import com.mewchat.tool.ToolResult;
import com.mewchat.tool.order.OrderTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物流查询工具：按运单号（或订单号）查出物流状态、最新轨迹与预计送达时间。
 *
 * <p><b>为什么入参接受两种单号</b>：物流系统只认运单号，但用户手上通常只有订单号 ——
 * 运单号印在面单上，用户往往看不到。若只收运单号，"我的包裹到哪了"这种再正常不过的
 * 问题会变成"请提供运单号"，而用户根本不知道去哪里找。因此这里两种标识都收，
 * 运单号优先。
 *
 * <p><b>订单号能查物流，靠的是订单上的运单号</b>：{@link #resolveTrackingNo} 通过
 * {@link OrderTool} 把订单号换成运单号。这里是工具之间的依赖，而不是在物流的模拟数据里
 * 再抄一份"订单号 → 运单号"的映射 —— 两份映射迟早会不一致，
 * 而"按订单号查到的运单号"永远以订单为准才是对的。真实接入时这里换成订单查询服务，
 * 结构与依赖方向都不变。
 *
 * <p><b>当前是进程内模拟数据</b>（{@link #MOCK_LOGISTICS}）：真实场景下物流轨迹来自
 * 快递公司的查询接口（或对接的物流聚合服务），接入时替换 {@link #queryLogistics} 的取数实现。
 *
 * @author MewChat
 */
@Component
public class LogisticsTool implements BusinessTool {

    /** 工具名，注解与本常量引用同一个值，避免两处写歪 */
    public static final String NAME = "logistics_query";

    private static final Logger log = LoggerFactory.getLogger(LogisticsTool.class);

    /** 摘要里的时间格式 */
    private static final DateTimeFormatter SUMMARY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 模拟物流数据，键为运单号。
     *
     * <p>轨迹按<b>时间倒序</b>存放（最新的在前）：物流查询永远只关心"现在到哪了"，
     * 按时间正序存放则每次取"最新"都要遍历一遍并承担顺序写错的风险。
     */
    private static final Map<String, LogisticsInfo> MOCK_LOGISTICS = buildMockLogistics();

    private final OrderTool orderTool;

    public LogisticsTool(OrderTool orderTool) {
        this.orderTool = orderTool;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResult invoke(Map<String, Object> params) {
        LogisticsQuery query = LogisticsQuery.from(params);

        if (query.trackingNo() == null && query.orderNo() == null) {
            // 两个标识都没给，连"查什么"都无从确定，只能反问
            return ToolResult.needMoreInfo(NAME, List.of("trackingNo"));
        }

        // 收集所有可能的运单号候选，依次尝试。
        // 之所以要试多个而不是"有运单号就只用运单号"：参数由大模型抽取，
        // 它可能把订单号填进了运单号键，也可能把同一个号码同时填进两个键。
        // 多试一个候选的代价只是一次内存查找，却能救回一整类输入错误。
        List<String> candidates = new ArrayList<>(3);
        addCandidate(candidates, query.trackingNo());
        // 订单号反查运单号：运单号挂在订单上，这是"用户只有订单号"时唯一的取数路径
        addCandidate(candidates, resolveTrackingNo(query.orderNo()));
        // 把用户给的"运单号"也当订单号试一次：模型填错键位是最常见的抽取错误
        addCandidate(candidates, resolveTrackingNo(query.trackingNo()));

        if (candidates.isEmpty()) {
            // 运单号换不出来有两种完全不同的成因，话术必须分开：
            //   ① 订单号本身查不到 —— 用户把号码记错了/写错了，该请他核对号码；
            //   ② 订单查得到、但还没生成运单号（未发货）—— 号码完全正确，
            //      该说的是"还没发货"，而不是让他去核对一个没错的号码。
            // 混成同一句"没查到该订单号，请核对"，用户会反复核对正确的号码，
            // 而真正的原因（尚未发货）始终没被告知
            String given = query.orderNo() != null ? query.orderNo() : query.trackingNo();
            OrderTool.OrderInfo order = orderTool.queryOrder(new OrderTool.OrderQuery(given));
            if (order != null) {
                log.debug("订单存在但尚未生成运单号：orderNo={} status={}", order.orderNo(), order.status());
                // 这是一次"成功"的查询：工具确实回答了用户的问题（还没发货），
                // 若按 notFound 处理会被反问"请核对订单号"，属于答非所问
                return ToolResult.ok(NAME, notShippedSummary(order), notShippedData(order));
            }
            return ToolResult.notFound(NAME,
                    "抱歉，没有查到与「" + given + "」对应的订单，"
                            + "所以暂时查不到它的物流。麻烦您核对一下订单号或运单号。");
        }

        for (String candidate : candidates) {
            LogisticsInfo info = queryLogistics(new LogisticsQuery(candidate, query.orderNo()));
            if (info != null) {
                log.debug("物流查询成功：trackingNo={} status={}", info.trackingNo(), info.status());
                return ToolResult.ok(NAME, info.summary(), info.toData());
            }
        }

        log.debug("运单不存在：candidates={}", candidates);
        return ToolResult.notFound(NAME,
                "抱歉，没有查到运单「" + candidates.get(0) + "」的物流信息，"
                        + "麻烦您核对一下运单号是否正确。");
    }

    /**
     * 向候选列表追加一个非空且未出现过的运单号。
     *
     * <p>去重是有意义的：模型常常把同一个号码同时填进运单号与订单号两个键，
     * 由它反查出的运单号就会与直接取到的那个重复，白查一次。
     *
     * @param candidates 候选列表，原地修改
     * @param value      待追加的值，可为 null
     */
    private static void addCandidate(List<String> candidates, String value) {
        if (StringUtils.hasText(value) && !candidates.contains(value)) {
            candidates.add(value);
        }
    }

    /**
     * 物流缺标识时同样是"选一个订单"，候选项来自订单工具。
     *
     * <p>不在物流侧另造一份订单列表：运单号本来就挂在订单上，
     * 两份数据迟早会不一致，而"按订单号查物流"必须以订单为准。
     *
     * @param userId 当前登录用户ID
     * @return 候选项列表
     */
    @Override
    public List<ClarificationOption> listOptions(Long userId) {
        return orderTool.listOptions(userId);
    }

    /**
     * 查询物流轨迹。
     *
     * <p>同时被编排层的确定性调用（{@link #invoke}）与将来模型自主选择工具时的调用
     * （靠 {@code @Tool} 生成的工具描述）使用。
     *
     * @param query 查询条件
     * @return 物流详情；运单号为空或查不到时返回 null
     */
    @Tool(name = NAME, value = "根据运单号（或订单号）查询物流轨迹，返回物流状态、最新轨迹与预计送达时间")
    public LogisticsInfo queryLogistics(@P("物流查询条件，运单号或订单号至少提供一个") LogisticsQuery query) {
        if (query == null || !StringUtils.hasText(query.trackingNo())) {
            return null;
        }
        return MOCK_LOGISTICS.get(query.trackingNo().trim());
    }

    /**
     * 由订单号换取运单号。
     *
     * @param orderNo 订单号，可为 null
     * @return 运单号；订单不存在或该订单尚未发货时返回 null
     */
    private String resolveTrackingNo(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            return null;
        }
        OrderTool.OrderInfo order = orderTool.queryOrder(new OrderTool.OrderQuery(orderNo));
        return order == null ? null : order.trackingNo();
    }

    /**
     * 生成"订单尚未发货"的摘要。
     *
     * <p>用订单自己的状态措辞，而不是写死一句"未发货"：订单状态可能是
     * 待发货、待付款、已取消，把它们都叫"未发货"会让用户以为订单已经成立。
     *
     * @param order 订单详情
     * @return 摘要文本
     */
    private static String notShippedSummary(OrderTool.OrderInfo order) {
        return "订单「" + order.orderNo() + "」当前状态为「" + order.status()
                + "」，商家还未生成运单号，因此暂时没有物流信息。"
                + "发出后即可查询轨迹。";
    }

    /**
     * 生成"订单尚未发货"的结构化数据。
     *
     * <p>不返回运单号/轨迹字段（它们本来就不存在），只给出订单号与状态，
     * 让接口层能如实渲染而不是显示一堆空占位。
     *
     * @param order 订单详情
     * @return 字段名到取值的映射
     */
    private static Map<String, Object> notShippedData(OrderTool.OrderInfo order) {
        Map<String, Object> data = new LinkedHashMap<>(3);
        data.put("orderNo", order.orderNo());
        data.put("status", order.status());
        data.put("shipped", false);
        return data;
    }

    /**
     * 构造模拟物流数据。
     *
     * <p>与模拟订单保持一致的三种形态：运输中、待揽收（商家已录单未发货）、已签收。
     *
     * @return 不可变的物流表
     */
    private static Map<String, LogisticsInfo> buildMockLogistics() {
        Map<String, LogisticsInfo> logistics = new LinkedHashMap<>();

        logistics.put("SF1234567890", new LogisticsInfo(
                "SF1234567890", "顺丰速运", "运输中",
                LocalDateTime.of(2024, 9, 25, 18, 0),
                List.of(
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 23, 8, 15),
                                "快件已到达【杭州西湖集散中心】"),
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 22, 21, 40),
                                "快件已从【南京转运中心】发出，下一站【杭州西湖集散中心】"),
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 22, 15, 20),
                                "顺丰速运已收取快件"))));

        logistics.put("ZT9876543210", new LogisticsInfo(
                "ZT9876543210", "中通快递", "待揽收",
                LocalDateTime.of(2024, 9, 26, 18, 0),
                List.of(
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 22, 9, 40),
                                "商家已打印运单，等待快递员上门揽收"))));

        logistics.put("YT5566778899", new LogisticsInfo(
                "YT5566778899", "圆通速递", "已签收",
                null,
                List.of(
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 13, 16, 22),
                                "快件已签收，签收人：本人"),
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 13, 9, 5),
                                "快件正在派送中，派送员正在为您派送"),
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 12, 19, 30),
                                "快件已到达【深圳南山网点】"),
                        new LogisticsTrace(LocalDateTime.of(2024, 9, 11, 10, 12),
                                "圆通速递已收取快件"))));

        return Collections.unmodifiableMap(logistics);
    }

    /* ==================== 入参 / 出参 ==================== */

    /**
     * 物流查询入参。
     *
     * <p>两个字段都是可选的，但<b>至少要有一个</b> —— 这种"二者取一"的约束无法用
     * 意图声明里的必需参数列表表达（那里是"全部必需"的语义），
     * 所以由工具自己判断，缺信息时返回 {@link ToolResult#needMoreInfo}。
     *
     * @param trackingNo 运单号，可选
     * @param orderNo    订单号，可选；没给运单号时用它反查运单号
     */
    public record LogisticsQuery(String trackingNo, String orderNo) {

        /**
         * 从参数表绑定入参。
         *
         * @param params 参数表，允许为 null
         * @return 入参对象，缺字段时为 null 字段
         */
        public static LogisticsQuery from(Map<String, Object> params) {
            // 限定接口名调用：接口的静态方法不会被实现类继承
            return new LogisticsQuery(BusinessTool.stringParam(params, "trackingNo"),
                    BusinessTool.stringParam(params, "orderNo"));
        }
    }

    /**
     * 物流详情出参。
     *
     * <p><b>最新轨迹不单独存字段</b>，而是由 {@link #latestTrace()} 从轨迹列表取第一条：
     * 存两份迟早会出现"最新轨迹"与"轨迹明细第一条"互相矛盾的情况，
     * 而排查这种矛盾要翻两处代码。倒序存放（见 {@link #MOCK_LOGISTICS}）让取第一条即最新。
     *
     * @param trackingNo            运单号
     * @param carrier               承运商
     * @param status                物流状态（运输中 / 待揽收 / 已签收……）
     * @param estimatedDeliveryTime 预计送达时间；已签收时为 null
     * @param traces                轨迹明细，按时间倒序，最新的在前
     */
    public record LogisticsInfo(String trackingNo,
                                String carrier,
                                String status,
                                LocalDateTime estimatedDeliveryTime,
                                List<LogisticsTrace> traces) {

        /**
         * 规范构造器：把 {@code traces} 统一成"非 null、不可变"的列表。
         *
         * <p>原先 {@code latestTrace()} 把它当作可能为空，而 {@code summary()} 与
         * {@code toData()} 却直接遍历/取 {@code size()} —— 同一个字段两处假设不一致，
         * 构造方一旦漏填轨迹，前一处安全、后两处直接 NPE。
         * 把假设收敛到构造器里，下游就都不必再判空（也不可能忘）。
         *
         * @param traces 轨迹明细，允许为 null
         */
        public LogisticsInfo {
            traces = traces == null ? List.of() : List.copyOf(traces);
        }

        /**
         * 取最新一条轨迹。
         *
         * @return 最新轨迹；无轨迹时返回 null
         */
        public LogisticsTrace latestTrace() {
            return CollectionUtils.isEmpty(traces) ? null : traces.get(0);
        }

        /**
         * 生成给大模型看的自然语言摘要。
         *
         * @return 摘要文本
         */
        public String summary() {
            LogisticsTrace latest = latestTrace();
            StringBuilder builder = new StringBuilder();
            builder.append("运单号 ").append(trackingNo)
                    .append('（').append(carrier).append("），物流状态：").append(status).append('\n');
            builder.append("最新轨迹：").append(latest == null
                    ? "暂无轨迹" : formatTime(latest.time()) + " " + latest.description()).append('\n');

            // 已签收的包裹没有"预计送达"，此时说预计时间会让用户以为还在路上
            if (estimatedDeliveryTime != null) {
                builder.append("预计送达时间：").append(formatTime(estimatedDeliveryTime)).append('\n');
            } else if ("已签收".equals(status)) {
                builder.append("该包裹已签收，无预计送达时间\n");
            }

            builder.append("完整轨迹：");
            for (LogisticsTrace trace : traces) {
                builder.append('\n').append(formatTime(trace.time())).append(' ').append(trace.description());
            }
            return builder.toString();
        }

        /**
         * 生成给接口层的结构化数据。
         *
         * @return 字段名到取值的映射
         */
        public Map<String, Object> toData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("trackingNo", trackingNo);
            data.put("carrier", carrier);
            data.put("status", status);
            data.put("estimatedDeliveryTime", estimatedDeliveryTime);

            LogisticsTrace latest = latestTrace();
            data.put("latestTraceTime", latest == null ? null : latest.time());
            data.put("latestTraceDesc", latest == null ? null : latest.description());

            List<Map<String, Object>> traceList = new ArrayList<>(traces.size());
            for (LogisticsTrace trace : traces) {
                Map<String, Object> item = new LinkedHashMap<>(2);
                item.put("time", trace.time());
                item.put("description", trace.description());
                traceList.add(item);
            }
            data.put("traces", traceList);
            return data;
        }

        /**
         * 格式化时间。
         *
         * @param time 时间，可为 null
         * @return 格式化文本
         */
        private static String formatTime(LocalDateTime time) {
            return time == null ? "未知" : SUMMARY_TIME_FORMAT.format(time);
        }
    }

    /**
     * 物流轨迹节点。
     *
     * @param time        轨迹发生时间
     * @param description 轨迹描述
     */
    public record LogisticsTrace(LocalDateTime time, String description) {
    }
}
