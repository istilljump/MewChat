package com.mewchat.tool.refund;

import com.mewchat.tool.BusinessTool;
import com.mewchat.tool.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款政策查询工具：按商品类目查出退款规则与退换货期限。
 *
 * <p><b>为什么退款政策要有一个"按类目查表"的工具，而不是全靠知识库</b>：
 * 政策既有"原文"的一面（写在《退换货规则》里，适合语义检索），
 * 也有"表格"的一面（类目 → 期限天数，是结构化事实）。用户问"生鲜能退吗"时，
 * 语义检索有把服饰的 7 天无理由规则答给生鲜的风险，而按类目查表不会 ——
 * 类目对上就是对上，对不上就走通用规则。两种能力并存，各答各擅长的问法。
 *
 * <p><b>当前编排还没有把它接进意图路由</b>：{@code REFUND_ASK} 意图目前路由到 RAG
 * （见 {@code IntentType}），因此本工具眼下只能按工具名 {@link #NAME} 调用。
 * 若要改成"类目明确时走查表、其余走检索"，在 {@code ToolSpecialist} 的
 * "意图 → 工具名"映射里加一行即可，工具层无需改动。
 *
 * <p><b>当前是进程内模拟政策表</b>（{@link #POLICIES}）：真实场景下这类规则通常维护在
 * 管理后台（落库）或 CMS 里，由运营调整。接入时替换 {@link #queryPolicy} 的取数实现，
 * 并且要记得<b>政策变更要能即时生效</b> —— 政策表被缓存住而运营改了规则，
 * 客服就会照旧规则答复用户，这是会产生客诉的错误。
 *
 * @author MewChat
 */
@Component
public class RefundTool implements BusinessTool {

    /** 工具名，注解与本常量引用同一个值，避免两处写歪 */
    public static final String NAME = "refund_policy_query";

    private static final Logger log = LoggerFactory.getLogger(RefundTool.class);

    /** 未收录具体类目时使用的通用政策键 */
    private static final String GENERAL_CATEGORY = "通用";

    /**
     * 模拟退款政策表，键为商品类目。
     *
     * <p>刻意包含一个"不支持无理由退货"的类目（生鲜）：只造支持退货的样例，
     * 会让"不支持"这条分支既写不对也测不到，而它恰恰是最容易答错、最容易引起纠纷的一类问题。
     */
    private static final Map<String, RefundPolicy> POLICIES = buildPolicies();

    /**
     * 类目检索词到标准类目的映射。
     *
     * <p>用户说的是商品名（"耳机""连衣裙"），不是平台内部的类目名（"数码配件"），
     * 大模型抽取出的 {@code category} 因此也总是商品词。没有这层映射，
     * 除了恰好说出标准类目名的问法，其余全部会落到通用政策上 ——
     * 这个工具也就等于没接。用 LinkedHashMap 保证遍历顺序稳定可复现。
     */
    private static final Map<String, String> CATEGORY_ALIASES = buildCategoryAliases();

    /**
     * 按关键词长度降序排好的别名表，供 {@link #matchByAlias} 遍历。
     *
     * <p><b>为什么必须按长度降序而不是照插入顺序</b>：匹配用的是
     * {@code raw.contains(别名)}，而别名之间有包含关系 —— 单字的"衣"是
     * "洗衣机"的子串。照插入顺序先撞上"衣"，"洗衣机""洗衣液"就都会被判成
     * 服饰鞋包，用户得到的答复是"吊牌需完整、未洗涤未穿着"，
     * 这是一条<b>内容完全错误但格式完全正常</b>的业务答复。
     * 先长后短则"洗衣机"会先被匹配到，单字别名只在该分类确实没有更具体的词时才生效。
     */
    private static final List<Map.Entry<String, String>> ALIASES_BY_LENGTH_DESC =
            CATEGORY_ALIASES.entrySet().stream()
                    .sorted(Comparator.comparingInt(
                            (Map.Entry<String, String> entry) -> entry.getKey().length()).reversed())
                    .toList();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResult invoke(Map<String, Object> params) {
        RefundQuery query = RefundQuery.from(params);
        if (!StringUtils.hasText(query.category())) {
            // 类目是这类问题的唯一入口，没给就问
            return ToolResult.needMoreInfo(NAME, List.of("category"));
        }

        RefundPolicy policy = queryPolicy(query);
        if (policy == null) {
            // 只会发生在政策表里连"通用"都缺失的配置错误上
            log.error("退款政策表缺少通用兜底条目，category={}", query.category());
            return ToolResult.fail(NAME, "退款政策表配置异常：缺少通用政策");
        }

        log.debug("退款政策查询完成：category={} matched={} 退货期限={}天",
                policy.category(), policy.matched(), policy.returnWindowDays());
        return ToolResult.ok(NAME, policy.summary(), policy.toData());
    }

    /**
     * 按类目查询退款政策。
     *
     * <p>同时被编排层的确定性调用（{@link #invoke}）与将来模型自主选择工具时的调用
     * （靠 {@code @Tool} 生成的工具描述）使用。
     *
     * @param query 查询条件
     * @return 退款政策；类目为空时返回 null。
     *         <b>类目未收录不会返回 null</b>，而是返回通用政策并将
     *         {@code matched} 置为 false —— "没收录这个类目"和"没有政策"是两回事，
     *         前者仍能给出一个诚实的答案
     */
    @Tool(name = NAME, value = "按商品类目查询退款与退换货政策，返回退款规则、退货期限与换货期限")
    public RefundPolicy queryPolicy(@P("退款政策查询条件，商品类目为必填项") RefundQuery query) {
        if (query == null || !StringUtils.hasText(query.category())) {
            return null;
        }

        String raw = query.category().trim();
        RefundPolicy exact = POLICIES.get(raw);
        if (exact != null) {
            return exact;
        }

        String aliased = matchByAlias(raw);
        if (aliased != null) {
            RefundPolicy policy = POLICIES.get(aliased);
            if (policy != null) {
                return policy;
            }
        }

        return POLICIES.get(GENERAL_CATEGORY);
    }

    /**
     * 用商品词匹配标准类目。
     *
     * <p>按关键词长度从长到短匹配，理由见 {@link #ALIASES_BY_LENGTH_DESC}：
     * 否则单字别名会把更具体的词（"洗衣机"）抢走。
     *
     * @param raw 用户给出的类目词
     * @return 标准类目名；匹配不到返回 null
     */
    private String matchByAlias(String raw) {
        for (Map.Entry<String, String> entry : ALIASES_BY_LENGTH_DESC) {
            if (raw.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /* ==================== 模拟政策数据 ==================== */

    /**
     * 构造模拟退款政策表。
     *
     * @return 不可变的政策表
     */
    private static Map<String, RefundPolicy> buildPolicies() {
        Map<String, RefundPolicy> policies = new LinkedHashMap<>();

        policies.put("数码配件", new RefundPolicy(
                "数码配件", true, 7, 15,
                List.of("支持 7 天无理由退货，商品需保持外观完好、配件与包装齐全",
                        "已激活或已拆封的手机、平板等设备不支持无理由退货",
                        "因质量问题退换货，往返运费由平台承担"),
                "人为损坏、进液、私自拆机不在退换范围内。"));

        policies.put("服饰鞋包", new RefundPolicy(
                "服饰鞋包", true, 7, 15,
                List.of("支持 7 天无理由退货，吊牌需完整、未洗涤未穿着",
                        "内衣、袜子等贴身类商品因卫生要求不支持无理由退货",
                        "换货运费：非质量问题时由买家承担"),
                "尺码不合属于无理由退货范围，可在订单页直接申请。"));

        policies.put("美妆个护", new RefundPolicy(
                "美妆个护", true, 7, 7,
                List.of("未拆封商品支持 7 天无理由退货",
                        "已拆封的化妆品、洗护用品因卫生要求不支持无理由退货",
                        "商品与描述不符、破损、临期等问题可全额退款"),
                "开封后出现过敏等不适，请保留就医凭证联系客服处理。"));

        policies.put("食品生鲜", new RefundPolicy(
                "食品生鲜", true, 0, 0,
                List.of("生鲜类商品不支持 7 天无理由退货",
                        "签收后 24 小时内发现变质、缺斤少两可申请质量问题退款",
                        "需提供商品与面单合照作为凭证"),
                "生鲜商品请尽量当面签收验货，签收后超过 24 小时不再受理质量异议。"));

        policies.put("家具家电", new RefundPolicy(
                "家具家电", true, 7, 30,
                List.of("支持 7 天无理由退货，但需未安装、未使用且包装完好",
                        "已安装的大家电退换需工程师上门检测后判定",
                        "大件商品退换由平台安排上门取件，非质量问题运费由买家承担"),
                "涉及上门安装的商品，请勿自行拆箱，以免影响退换。"));

        policies.put(GENERAL_CATEGORY, new RefundPolicy(
                GENERAL_CATEGORY, false, 7, 15,
                List.of("支持 7 天无理由退货，自签收之日起算，商品需不影响二次销售",
                        "定制类商品、已拆封的数字化商品、个人护理用品不支持无理由退货",
                        "因质量问题产生的退换货运费由平台承担"),
                "不确定商品是否支持无理由退货时，可直接提供订单号由客服核实。"));

        return Collections.unmodifiableMap(policies);
    }

    /**
     * 构造类目检索词映射。
     *
     * @return 不可变的映射表
     */
    private static Map<String, String> buildCategoryAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("耳机", "数码配件");
        aliases.put("手机", "数码配件");
        aliases.put("电脑", "数码配件");
        aliases.put("键盘", "数码配件");
        aliases.put("数码", "数码配件");
        aliases.put("配件", "数码配件");
        aliases.put("衣", "服饰鞋包");
        aliases.put("裤", "服饰鞋包");
        aliases.put("裙", "服饰鞋包");
        aliases.put("鞋", "服饰鞋包");
        aliases.put("包", "服饰鞋包");
        aliases.put("化妆", "美妆个护");
        aliases.put("护肤", "美妆个护");
        aliases.put("洗护", "美妆个护");
        aliases.put("个护", "美妆个护");
        aliases.put("食品", "食品生鲜");
        aliases.put("生鲜", "食品生鲜");
        aliases.put("水果", "食品生鲜");
        aliases.put("零食", "食品生鲜");
        aliases.put("家电", "家具家电");
        aliases.put("家具", "家具家电");
        aliases.put("冰箱", "家具家电");
        aliases.put("洗衣机", "家具家电");
        return Collections.unmodifiableMap(aliases);
    }

    /* ==================== 入参 / 出参 ==================== */

    /**
     * 退款政策查询入参。
     *
     * @param category 商品类目，必填。允许是平台标准类目名或用户口中的商品词
     */
    public record RefundQuery(String category) {

        /**
         * 从参数表绑定入参。
         *
         * @param params 参数表，允许为 null
         * @return 入参对象，缺字段时为 null 字段
         */
        public static RefundQuery from(Map<String, Object> params) {
            // 限定接口名调用：接口的静态方法不会被实现类继承
            return new RefundQuery(BusinessTool.stringParam(params, "category"));
        }
    }

    /**
     * 退款政策出参。
     *
     * <p>期限用<b>天数</b>而不是"7天"这样的字符串：前端要按天数算剩余时间、
     * 要按它做倒计时提示，给字符串等于把解析工作推给每个下游。
     *
     * @param category         命中的类目名（未收录时为"通用"）
     * @param matched          是否命中了该类目的专属政策
     * @param returnWindowDays 退货期限（天）；0 表示不支持无理由退货
     * @param exchangeWindowDays 换货期限（天）；0 表示不支持换货
     * @param rules            退款规则要点
     * @param note             附加说明
     */
    public record RefundPolicy(String category,
                               boolean matched,
                               int returnWindowDays,
                               int exchangeWindowDays,
                               List<String> rules,
                               String note) {

        /**
         * 生成给大模型看的自然语言摘要。
         *
         * @return 摘要文本
         */
        public String summary() {
            StringBuilder builder = new StringBuilder();
            if (matched) {
                builder.append("商品类目「").append(category).append("」的退换货政策：\n");
            } else {
                // 未收录时必须说清楚，否则模型会把通用规则当成该类目的专属规则讲给用户
                builder.append("政策表里没有「").append(category)
                        .append("」这一类目的专属政策，以下是平台通用规则：\n");
            }

            builder.append(describeWindow("退货", returnWindowDays))
                    .append('\n')
                    .append(describeWindow("换货", exchangeWindowDays))
                    .append('\n')
                    .append("规则要点：");
            for (String rule : rules) {
                builder.append("\n- ").append(rule);
            }
            if (StringUtils.hasText(note)) {
                builder.append("\n说明：").append(note);
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
            data.put("category", category);
            data.put("matched", matched);
            data.put("returnWindowDays", returnWindowDays);
            data.put("exchangeWindowDays", exchangeWindowDays);
            data.put("supportNoReasonReturn", returnWindowDays > 0);
            data.put("rules", new ArrayList<>(rules));
            data.put("note", note);
            return data;
        }

        /**
         * 描述一个期限。
         *
         * <p>天数为 0 时输出"不支持…"而不是"期限 0 天"：后者语法上成立、
         * 语义上荒谬，模型抄进回复里就是一句用户看不懂的话。
         *
         * @param action 动作名，如"退货"
         * @param days   天数，0 表示不支持
         * @return 描述文本
         */
        private static String describeWindow(String action, int days) {
            return days > 0 ? action + "期限：" + days + " 天（自签收之日起）"
                    : action + "期限：不支持无理由" + action;
        }
    }
}
