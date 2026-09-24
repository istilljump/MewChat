package com.mewchat.tool.product;

import com.mewchat.tool.ToolResult;
import com.mewchat.tool.refund.RefundTool;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品查询工具的单元测试（不依赖 Spring 与数据库）。
 *
 * <p>重点覆盖三类分支：查到商品（含"用户只说了一半"的关键词问法）、
 * 没给商品名、商品名给错 —— 后两类是客服里最常见的输入问题，
 * 也最容易实现成"返回失败"，那样用户会被告知"系统忙、建议转人工"，
 * 而实际上缺的只是一句反问。
 *
 * <p>还有一条别的工具没有的断言：<b>商品类目必须与退款政策表对得上</b>。
 * 两处各写一份类目迟早会出现"商品工具说这是数码配件、退款工具查不到这个类目"，
 * 而用户看到的将是一个自相矛盾的答复。
 *
 * @author MewChat
 */
class ProductToolTest {

    private final ProductTool productTool = new ProductTool();

    private final RefundTool refundTool = new RefundTool();

    /**
     * 工具名必须与编排侧引用的常量一致，否则调用时报"未注册的工具"。
     */
    @Test
    void nameShouldBeStable() {
        assertThat(productTool.name()).isEqualTo("product_query");
        assertThat(productTool.name()).isEqualTo(ProductTool.NAME);
    }

    /**
     * 候选项要填的参数名必须由工具自己声明。
     */
    @Test
    void clarificationParamShouldBeProductName() {
        assertThat(productTool.clarificationParam()).isEqualTo(ProductTool.PARAM_PRODUCT_NAME);
    }

    /**
     * 用商品全名查询时，金额是数值、库存是整数、在售是布尔值。
     *
     * <p>给字符串等于把解析工作推给每个下游：前端要做数字比较、模型要猜
     * "¥499.00"里哪个是价格。
     */
    @Test
    void shouldReturnProductDetail() {
        ToolResult result = productTool.invoke(Map.of("productName", "无线蓝牙耳机 Pro"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("无线蓝牙耳机 Pro").contains("499").contains("在售");
        assertThat(result.getData()).containsEntry("category", "数码配件");
        assertThat(result.getData().get("price")).isInstanceOf(BigDecimal.class);
        assertThat(result.getData().get("stock")).isInstanceOf(Integer.class);
        assertThat(result.getData().get("onSale")).isEqualTo(true);
    }

    /**
     * 用户只说一半时也要能命中："耳机"要落到"无线蓝牙耳机 Pro"。
     *
     * <p>真实对话里用户很少说全名 —— 模型抽取出来的往往就是"耳机"这种商品词。
     * 只支持全名匹配的话，这个工具在实际对话里等于不可用。
     */
    @Test
    void partialNameShouldStillMatch() {
        assertThat(productTool.invoke(Map.of("productName", "耳机")).getData())
                .containsEntry("productName", "无线蓝牙耳机 Pro");
        assertThat(productTool.invoke(Map.of("productName", "键盘")).getData())
                .containsEntry("productName", "机械键盘 87 键");
        // 带前后缀的问法同样要命中
        assertThat(productTool.invoke(Map.of("productName", "这个无线蓝牙耳机 Pro 多少钱")).getData())
                .containsEntry("productName", "无线蓝牙耳机 Pro");
    }

    /**
     * 缺货商品必须如实说明"缺货"，而不是只给一个库存 0。
     *
     * <p>用户问"还有货吗"，最关心的就是能不能买到；回答里不点明缺货，
     * 用户会以为可以直接下单。
     */
    @Test
    void outOfStockProductShouldSaySo() {
        ToolResult result = productTool.invoke(Map.of("productName", "充电宝"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("stock", 0).containsEntry("onSale", false);
        assertThat(result.getSummary()).contains("缺货");
    }

    /**
     * 没给商品名时走追问，而不是拿空参数去查。
     */
    @Test
    void missingProductNameShouldAskForIt() {
        ToolResult result = productTool.invoke(new HashMap<>());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getMissingParams()).containsExactly(ProductTool.PARAM_PRODUCT_NAME);
    }

    /**
     * 空白商品名等同于没给。
     */
    @Test
    void blankProductNameShouldBeTreatedAsMissing() {
        ToolResult result = productTool.invoke(Map.of("productName", "   "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMissingParams()).containsExactly(ProductTool.PARAM_PRODUCT_NAME);
    }

    /**
     * 商品名给错时应引导用户核对，并回显他给的原词。
     *
     * <p>回显是关键：用户看到"没查到「蓝牙音箱」"才知道自己说的是哪件，
     * 否则会以为自己没说清而换个说法再问一遍。
     */
    @Test
    void unknownProductShouldAskToVerify() {
        ToolResult result = productTool.invoke(Map.of("productName", "蓝牙音箱"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getClarifyHint()).contains("蓝牙音箱").contains("核对");
    }

    /**
     * 候选商品只列在售的，且话术里带上类目与价格供用户辨认。
     *
     * <p>把缺货商品列进候选让用户挑，挑完得到的回答是"没货" —— 不如一开始就不列。
     */
    @Test
    void candidatesShouldOnlyContainOnSaleProducts() {
        assertThat(productTool.listOptions(null))
                .as("商品是公开目录，没有身份也应列得出（与订单候选相反）")
                .isNotEmpty()
                .allSatisfy(option -> {
                    assertThat(option.value()).isNotBlank();
                    assertThat(option.label()).contains("¥");
                })
                .noneSatisfy(option -> assertThat(option.value()).isEqualTo("便携充电宝 10000mAh"));

        assertThat(productTool.listOptions(12345L))
                .as("商品候选不按用户过滤：商品目录是公开数据，谁都能看")
                .hasSameSizeAs(productTool.listOptions(null));
    }

    /**
     * 每个商品的类目，退款政策表里都必须查得到具体政策。
     *
     * <p>这条断言防的是两份模拟数据悄悄分叉：商品工具说"这是家具家电"、
     * 退款工具却查不到这个类目而落到"通用"政策 —— 用户会看到一个自相矛盾的系统。
     * 真实接入后两份数据来自不同系统，这个风险只会更大。
     */
    @Test
    void everyCategoryShouldExistInRefundPolicy() {
        for (String productName : ProductTool.allProductNames()) {
            String category = String.valueOf(
                    productTool.invoke(Map.of("productName", productName)).getData().get("category"));

            Object matchedCategory = refundTool.invoke(Map.of("category", category)).getData().get("category");

            assertThat(matchedCategory)
                    .as("商品「%s」的类目 %s 必须在退款政策里有具体条目（落到「通用」说明两处数据已分叉）",
                            productName, category)
                    .isEqualTo(category);
        }
    }

    /**
     * {@code @Tool} 方法对空入参返回 null，不抛异常。
     */
    @Test
    void toolMethodShouldHandleBlankInput() {
        assertThat(productTool.queryProduct(null)).isNull();
        assertThat(productTool.queryProduct(new ProductTool.ProductQuery(" "))).isNull();
        assertThat(productTool.queryProduct(new ProductTool.ProductQuery("保温杯"))).isNotNull();
    }
}
