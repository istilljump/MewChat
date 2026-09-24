package com.mewchat.tool.product;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品查询工具：按商品名查出价格、规格、库存与所属类目。
 *
 * <p><b>为什么商品查询需要工具，而不都交给知识检索</b>：商品描述、卖点、使用说明这类
 * 非结构化内容适合走 RAG；而"多少钱""还有货吗""什么规格"是<b>结构化事实</b>，
 * 必须查到确切的值。让模型从知识片段里"读出"价格，是编造数字最常见的入口 ——
 * 片段里可能写着"原价 599，活动价 499"，模型挑错一个数字，用户就会按错价格下单。
 *
 * <p><b>类目取值与退款政策表保持一致</b>：两处各写一份类目，迟早会出现
 * "商品工具说这是数码配件、退款工具查不到这个类目"的矛盾，而用户看到的
 * 是一个自相矛盾的答复。{@code ProductToolTest} 里有一条断言专门钉住这一点：
 * 每个商品的类目都必须在退款政策里能查到具体政策，而不是落到"通用"。
 *
 * <p><b>当前是进程内模拟数据</b>（{@link #MOCK_PRODUCTS}）：真实场景下商品数据
 * 来自商品中心。接入时替换 {@link #queryProduct} 的取数实现即可 ——
 * 上游只依赖 {@link BusinessTool} 契约。
 *
 * @author MewChat
 */
@Component
public class ProductTool implements BusinessTool {

    /**
     * 工具名。
     *
     * <p>注解与本常量引用的是同一个值，因此"编排侧按 {@code product_query} 调用、
     * 注解里却写着别的名字"这种错配不可能发生。
     */
    public static final String NAME = "product_query";

    /** 商品名参数名，澄清追问的候选项就填它（见 {@link #clarificationParam()}） */
    public static final String PARAM_PRODUCT_NAME = "productName";

    private static final Logger log = LoggerFactory.getLogger(ProductTool.class);

    /**
     * 模拟商品表，键为商品名。
     *
     * <p>商品名与订单里的商品名保持一致（同一件商品在两处必须叫同一个名字），
     * 否则用户拿订单里的商品名来问价格会查不到。
     */
    private static final Map<String, ProductInfo> MOCK_PRODUCTS = buildMockProducts();

    /**
     * 关键词到商品名的映射，按关键词长度降序排好。
     *
     * <p><b>必须按长度降序</b>：匹配用的是"用户的话里是否含这个词"，
     * 而词之间有包含关系 —— 先撞上短的（"耳机"）就会把更具体的
     * （"无线蓝牙耳机 Pro"）抢走。这与退款类目别名踩过的是同一个坑。
     */
    private static final List<Map.Entry<String, String>> KEYWORDS_BY_LENGTH_DESC =
            buildKeywords().entrySet().stream()
                    .sorted(Comparator.comparingInt(
                            (Map.Entry<String, String> entry) -> entry.getKey().length()).reversed())
                    .toList();

    /** 商品名按长度降序，用于"用户话里直接含商品全名"的匹配 */
    private static final List<String> PRODUCT_NAMES_BY_LENGTH_DESC = MOCK_PRODUCTS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String clarificationParam() {
        return PARAM_PRODUCT_NAME;
    }

    @Override
    public ToolResult invoke(Map<String, Object> params) {
        ProductQuery query = ProductQuery.from(params);
        if (!StringUtils.hasText(query.productName())) {
            // 没给商品名：走追问（会带上商品候选），这不是错误
            return ToolResult.needMoreInfo(NAME, List.of(PARAM_PRODUCT_NAME));
        }

        ProductInfo product = queryProduct(query);
        if (product == null) {
            log.debug("商品不存在：keyword={}", query.productName());
            return ToolResult.notFound(NAME,
                    "抱歉，没有查到「" + query.productName() + "」这件商品，"
                            + "麻烦您核对一下商品名称（订单详情里的商品名可以直接用）。");
        }

        log.debug("商品查询成功：name={} category={} stock={}",
                product.productName(), product.category(), product.stock());
        return ToolResult.ok(NAME, product.summary(), product.toData());
    }

    /**
     * 按商品名或关键词查询商品详情。
     *
     * <p>本方法同时被编排层的确定性调用（{@link #invoke}）与将来模型自主选择工具时的
     * 调用（靠 {@code @Tool} 生成的工具描述）使用。
     *
     * @param query 查询条件
     * @return 商品详情；商品名为空或查不到时返回 null
     */
    @Tool(name = NAME, value = "根据商品名称查询商品详情，返回售价、规格、库存与所属类目")
    public ProductInfo queryProduct(@P("商品查询条件，商品名称为必填项") ProductQuery query) {
        if (query == null || !StringUtils.hasText(query.productName())) {
            return null;
        }
        String raw = query.productName().trim();

        // ① 原名或全名直接命中：用户把订单里的商品名原样发过来是最常见的情况
        ProductInfo exact = MOCK_PRODUCTS.get(raw);
        if (exact != null) {
            return exact;
        }
        for (String productName : PRODUCT_NAMES_BY_LENGTH_DESC) {
            if (raw.contains(productName)) {
                return MOCK_PRODUCTS.get(productName);
            }
        }

        // ② 按关键词命中："耳机""键盘""T 恤"这类不完整的说法要能落到具体商品上
        for (Map.Entry<String, String> entry : KEYWORDS_BY_LENGTH_DESC) {
            if (raw.contains(entry.getKey())) {
                return MOCK_PRODUCTS.get(entry.getValue());
            }
        }
        return null;
    }

    /**
     * 列出全部在售商品，供"没给商品名"时的澄清追问使用。
     *
     * <p><b>这里不按 userId 过滤，与订单工具相反 —— 这是有意的</b>：
     * 商品是公开的商品目录，谁都能看；而订单属于某个用户，列出别人的订单
     * 就是泄露交易信息。两者的处理方式不同，正是因为数据的归属性质不同。
     *
     * <p>候选只列"在售"的：把缺货商品列进去让用户挑，挑完得到的回答是
     * "没货"，不如一开始就不列。
     *
     * @param userId 当前登录用户ID，本工具不使用（商品目录是公开数据）
     * @return 候选项列表
     */
    @Override
    public List<ClarificationOption> listOptions(Long userId) {
        return MOCK_PRODUCTS.values().stream()
                .filter(ProductInfo::onSale)
                .map(product -> new ClarificationOption(
                        product.productName(),
                        product.productName() + "（" + product.category() + "，¥"
                                + product.price().toPlainString() + "）"))
                .toList();
    }

    /**
     * 构造模拟商品数据。
     *
     * <p>覆盖多个类目、并刻意留一个缺货商品：让"还有货吗"能答出否定结果 ——
     * 全是"有货"的样例数据会让缺货分支永远测不到，而缺货恰恰是客服场景里
     * 最容易引发投诉的一类问题。
     *
     * @return 不可变的商品表
     */
    private static Map<String, ProductInfo> buildMockProducts() {
        Map<String, ProductInfo> products = new LinkedHashMap<>();

        products.put("无线蓝牙耳机 Pro", new ProductInfo(
                "无线蓝牙耳机 Pro", "数码配件", new BigDecimal("499.00"),
                "蓝牙 5.3 / 主动降噪 / 单次续航 8 小时（含仓 30 小时）", 128, true));

        products.put("机械键盘 87 键", new ProductInfo(
                "机械键盘 87 键", "数码配件", new BigDecimal("399.00"),
                "87 键 / 茶轴 / 有线 + 2.4G 双模 / PBT 键帽", 46, true));

        products.put("便携充电宝 10000mAh", new ProductInfo(
                "便携充电宝 10000mAh", "数码配件", new BigDecimal("129.00"),
                "10000mAh / 22.5W 快充 / 支持双向充电 / 可登机", 0, false));

        products.put("智能保温杯 500ml", new ProductInfo(
                "智能保温杯 500ml", "家具家电", new BigDecimal("258.00"),
                "500ml / 316 不锈钢内胆 / 触控显温 / 保温 12 小时", 75, true));

        products.put("纯棉圆领 T 恤", new ProductInfo(
                "纯棉圆领 T 恤", "服饰鞋包", new BigDecimal("89.00"),
                "100% 精梳棉 / 男女同款 / S-XXL / 5 色可选", 312, true));

        products.put("云南普洱熟茶 357g", new ProductInfo(
                "云南普洱熟茶 357g", "食品生鲜", new BigDecimal("168.00"),
                "357g 七子饼 / 五年陈 / 独立包装", 58, true));

        return Collections.unmodifiableMap(products);
    }

    /**
     * 构造关键词到商品名的映射。
     *
     * <p>关键词只放"用户真会这么说的商品词"，不放"杯""茶"这类单字 ——
     * 单字会把无关的话一并命中（"杯子"和"保温杯"都含"杯"，
     * 而"茶杯垫"也会被算成保温杯）。宁可匹配不到、由追问把商品列出来让用户选，
     * 也不要匹配错 —— 查错商品给出的价格比查不到更糟。
     *
     * @return 关键词映射
     */
    private static Map<String, String> buildKeywords() {
        Map<String, String> keywords = new LinkedHashMap<>();
        keywords.put("耳机", "无线蓝牙耳机 Pro");
        keywords.put("蓝牙耳机", "无线蓝牙耳机 Pro");
        keywords.put("键盘", "机械键盘 87 键");
        keywords.put("充电宝", "便携充电宝 10000mAh");
        keywords.put("充电器", "便携充电宝 10000mAh");
        keywords.put("保温杯", "智能保温杯 500ml");
        keywords.put("水杯", "智能保温杯 500ml");
        keywords.put("T 恤", "纯棉圆领 T 恤");
        keywords.put("T恤", "纯棉圆领 T 恤");
        keywords.put("普洱茶", "云南普洱熟茶 357g");
        keywords.put("熟茶", "云南普洱熟茶 357g");
        return keywords;
    }

    /* ==================== 入参 / 出参 ==================== */

    /**
     * 商品查询入参。
     *
     * @param productName 商品名或商品关键词，必填
     */
    public record ProductQuery(String productName) {

        /**
         * 从参数表绑定入参。
         *
         * <p>绑定逻辑写在入参自己身上，是为了让"模型给了什么键"与"工具要什么字段"
         * 这对关系紧挨在一起 —— 新增字段时在一个地方改完。
         *
         * @param params 参数表，允许为 null
         * @return 入参对象，缺字段时为 null 字段
         */
        public static ProductQuery from(Map<String, Object> params) {
            // 限定接口名调用：接口的静态方法不会被实现类继承
            return new ProductQuery(BusinessTool.stringParam(params, PARAM_PRODUCT_NAME));
        }
    }

    /**
     * 商品详情出参。
     *
     * <p>同时服务于两个下游：{@link #summary()} 供大模型组织话术，
     * {@link #toData()} 供接口层直接渲染商品卡片。
     *
     * @param productName 商品名称
     * @param category    所属类目，取值与退款政策表一致
     * @param price       售价
     * @param spec        规格说明
     * @param stock       库存件数
     * @param onSale      是否在售（库存为 0 时为 false）
     */
    public record ProductInfo(String productName,
                             String category,
                             BigDecimal price,
                             String spec,
                             Integer stock,
                             boolean onSale) {

        /**
         * 生成给大模型看的自然语言摘要。
         *
         * <p>查到的数据本身就是事实，把事实整理成短句交给模型，它只需组织语气。
         * 缺货时必须把"缺货"说清楚：模型看到"库存 0"未必会主动提，
         * 而用户最关心的恰恰是能不能买到。
         *
         * @return 摘要文本
         */
        public String summary() {
            return "商品 " + productName + "（类目 " + category + "）\n"
                    + "售价：¥" + price.toPlainString() + "\n"
                    + "规格：" + spec + "\n"
                    + "库存：" + stock + " 件"
                    + (onSale ? "（在售）" : "（缺货，暂时无法下单）");
        }

        /**
         * 生成给接口层的结构化数据。
         *
         * <p>金额是 {@code BigDecimal}、库存是整数、在售与否是布尔值：
         * 给字符串等于把解析工作推给每个下游。
         *
         * @return 字段名到取值的映射
         */
        public Map<String, Object> toData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("productName", productName);
            data.put("category", category);
            data.put("price", price);
            data.put("spec", spec);
            data.put("stock", stock);
            data.put("onSale", onSale);
            return data;
        }
    }

    /**
     * 供测试与排查使用：当前模拟商品的全部名称。
     *
     * @return 商品名列表（按录入顺序）
     */
    public static List<String> allProductNames() {
        return new ArrayList<>(MOCK_PRODUCTS.keySet());
    }
}
