package com.mewchat.tool.refund;

import com.mewchat.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款政策查询工具的单元测试（不依赖 Spring 与数据库）。
 *
 * <p>重点覆盖三件事：标准类目命中、用户口中的商品词能映射到类目、
 * 以及"不支持无理由退货"的类目（生鲜）不会被通用规则盖过去。
 * 最后一条是最容易出错也最容易引起客诉的分支 ——
 * 把"生鲜不支持无理由退货"答成"支持 7 天无理由"，用户按这个说法寄回商品会被拒收。
 *
 * @author MewChat
 */
class RefundToolTest {

    private final RefundTool refundTool = new RefundTool();

    /**
     * 按标准类目名查询。
     */
    @Test
    void shouldQueryByStandardCategory() {
        ToolResult result = refundTool.invoke(Map.of("category", "数码配件"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("7 天").contains("已激活");
        assertThat(result.getData())
                .containsEntry("category", "数码配件")
                .containsEntry("matched", true)
                .containsEntry("returnWindowDays", 7)
                .containsEntry("supportNoReasonReturn", true);
    }

    /**
     * 用户说的是商品词（"耳机"）而不是平台类目名，必须能映射到类目，
     * 否则这个工具对绝大多数真实问法都只会回通用政策、等于没接。
     */
    @Test
    void shouldMapProductWordToCategory() {
        ToolResult result = refundTool.invoke(Map.of("category", "耳机"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData())
                .containsEntry("category", "数码配件")
                .containsEntry("matched", true);
    }

    /**
     * 生鲜不支持无理由退货，这条不能被通用规则覆盖。
     */
    @Test
    void freshFoodShouldNotSupportNoReasonReturn() {
        ToolResult result = refundTool.invoke(Map.of("category", "生鲜"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData())
                .containsEntry("category", "食品生鲜")
                .containsEntry("matched", true)
                .containsEntry("returnWindowDays", 0)
                .containsEntry("supportNoReasonReturn", false);
        assertThat(result.getSummary())
                .contains("不支持无理由退货")
                .contains("24 小时");
    }

    /**
     * 未收录的类目要回落到通用政策，并且<b>必须说明这不是该类目的专属规则</b>。
     */
    @Test
    void unknownCategoryShouldFallBackToGeneralPolicyAndSaySo() {
        ToolResult result = refundTool.invoke(Map.of("category", "乐器"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData())
                .containsEntry("category", "通用")
                .containsEntry("matched", false);
        assertThat(result.getSummary())
                .as("必须让模型知道这是通用规则，否则它会当成该类目的专属政策讲给用户")
                .contains("没有")
                .contains("通用规则");
    }

    /**
     * 没给类目时应追问。
     */
    @Test
    void missingCategoryShouldAskForIt() {
        ToolResult result = refundTool.invoke(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.needsClarification()).isTrue();
        assertThat(result.getMissingParams()).containsExactly("category");
    }

    /**
     * 期限为 0 时不能输出"0 天"这种语法成立、语义荒谬的表述。
     */
    @Test
    void zeroWindowShouldReadAsUnsupported() {
        ToolResult result = refundTool.invoke(Map.of("category", "食品生鲜"));

        assertThat(result.getSummary())
                .contains("退货期限：不支持无理由退货")
                .contains("换货期限：不支持无理由换货")
                .doesNotContain("0 天");
    }

    /**
     * 结构化数据里的期限必须是数字，供前端计算剩余时间。
     */
    @Test
    void windowDaysShouldBeNumericForFrontend() {
        ToolResult result = refundTool.invoke(Map.of("category", "服饰鞋包"));

        assertThat(result.getData().get("returnWindowDays")).isInstanceOf(Integer.class);
        assertThat(result.getData().get("exchangeWindowDays")).isInstanceOf(Integer.class);
    }

    /**
     * 类目词首尾带空格时也要能命中。
     */
    @Test
    void categoryWithWhitespaceShouldBeTrimmed() {
        ToolResult result = refundTool.invoke(Map.of("category", "  生鲜  "));

        assertThat(result.getData()).containsEntry("category", "食品生鲜");
    }

    /**
     * {@code @Tool} 方法对空入参返回 null，不抛异常。
     */
    @Test
    void toolMethodShouldHandleBlankInput() {
        assertThat(refundTool.queryPolicy(null)).isNull();
        assertThat(refundTool.queryPolicy(new RefundTool.RefundQuery(" "))).isNull();
        assertThat(refundTool.queryPolicy(new RefundTool.RefundQuery("家电"))).isNotNull();
    }

    /**
     * 政策表必须始终有"通用"兜底条目，否则未收录类目会让用户拿到一个失败结果。
     */
    @Test
    void generalPolicyMustAlwaysExist() {
        assertThat(refundTool.queryPolicy(new RefundTool.RefundQuery("完全没听过的类目")))
                .as("通用政策是未收录类目的唯一兜底，不能缺失")
                .isNotNull();
    }

    /**
     * 别名匹配必须"先长后短"，否则单字别名会抢走更具体的词。
     *
     * <p>"衣"是"洗衣机"的子串。照别名表的插入顺序匹配时先撞上"衣"，
     * 洗衣机就会落到"服饰鞋包"，用户拿到的答复是"吊牌需完整、未洗涤未穿着" ——
     * 一条<b>内容错误但格式完全正常</b>的业务答复。
     */
    @Test
    void longerAliasShouldWinOverSingleCharAlias() {
        assertThat(refundTool.invoke(Map.of("category", "洗衣机")).getData())
                .as("洗衣机属于家具家电，不能被单字别名'衣'抢到服饰鞋包")
                .containsEntry("category", "家具家电");

        // 对照：确实没有更长别名可匹配时，单字别名仍要照常生效
        assertThat(refundTool.invoke(Map.of("category", "连衣裙")).getData())
                .as("单字别名在无更长匹配时依然要能用")
                .containsEntry("category", "服饰鞋包");
    }
}
