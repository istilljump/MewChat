package com.mewchat.service;

import com.mewchat.config.GuardrailProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全护栏的单元测试（不依赖 Spring）。
 *
 * <p>护栏是"看起来在工作"和"真的在工作"差别最大的一类代码：
 * 拿正常问题测它，永远是通过的；只有拿<b>刻意规避的写法</b>去测，
 * 才能发现它其实一绕就过。因此这里的重点是"刷 单""刷-单"这类变形。
 *
 * @author MewChat
 */
class GuardrailServiceTest {

    private final GuardrailService service =
            new GuardrailService(properties(true, "刷单", "代开发票", "违禁品"));

    /* ==================== 命中 ==================== */

    /**
     * 直接写出敏感词应被拦下。
     */
    @Test
    void shouldMatchConfiguredWord() {
        assertThat(service.match("请问怎么刷单")).contains("刷单");
        assertThat(service.match("能代开发票吗")).contains("代开发票");
    }

    /**
     * 加空格、插标点等规避写法同样要拦得住。
     *
     * <p>这是护栏有没有实际价值的分水岭：不做归一化的话，
     * 绕过它只需在词中间插一个空格。
     */
    @Test
    void shouldMatchObfuscatedInput() {
        assertThat(service.match("刷 单")).as("插空格").contains("刷单");
        assertThat(service.match("刷-单")).as("插连字符").contains("刷单");
        assertThat(service.match("刷【单】")).as("插括号").contains("刷单");
        assertThat(service.match("刷\n单")).as("插换行").contains("刷单");
    }

    /**
     * 正常咨询不能被误伤 —— 误拦的代价和漏拦一样高。
     */
    @Test
    void shouldNotMatchNormalQuestion() {
        assertThat(service.match("七天无理由退货怎么操作")).isEmpty();
        assertThat(service.match("我的订单到哪了")).isEmpty();
        assertThat(service.match("退款要多久到账")).isEmpty();
    }

    /* ==================== 边界 ==================== */

    /**
     * 护栏关闭时一律放行。
     */
    @Test
    void disabledShouldNotMatch() {
        GuardrailService disabled = new GuardrailService(properties(false, "刷单"));

        assertThat(disabled.match("怎么刷单")).isEmpty();
    }

    /**
     * 词表为空时不拦截任何内容（构造函数会告警，但不应报错）。
     */
    @Test
    void emptyWordListShouldNotMatch() {
        GuardrailService empty = new GuardrailService(properties(true));

        assertThat(empty.match("怎么刷单")).isEmpty();
    }

    /**
     * 配置里混入空白项时，不能把空串当成"命中一切"的词。
     */
    @Test
    void blankConfiguredWordsShouldBeIgnored() {
        GuardrailService withBlanks = new GuardrailService(properties(true, "", "   ", "刷单"));

        assertThat(withBlanks.match("你好"))
                .as("空词条必须被过滤掉，否则会命中所有输入")
                .isEmpty();
        assertThat(withBlanks.match("怎么刷单")).contains("刷单");
    }

    /**
     * 输入为空或 null 时不应抛异常。
     */
    @Test
    void matchShouldTolerateNullAndBlank() {
        assertThat(service.match(null)).isEmpty();
        assertThat(service.match("")).isEmpty();
        assertThat(service.match("   ")).isEmpty();
        assertThat(service.match("！！！")).as("只有标点时归一化后为空串").isEmpty();
    }

    /* ==================== 话术 ==================== */

    /**
     * 拒答话术里不能出现命中的词，也不说明"因为哪个词被拦下"。
     *
     * <p>否则等于把规则边界交出去，会被反复试探。
     */
    @Test
    void rejectReplyShouldNotEchoMatchedWords() {
        assertThat(service.rejectReply())
                .isNotBlank()
                .doesNotContain("刷单")
                .doesNotContain("代开发票")
                .doesNotContain("违禁品");
    }

    /* ==================== 辅助 ==================== */

    /**
     * 构造护栏配置。
     *
     * @param enabled 是否启用
     * @param words   敏感词
     * @return 配置对象
     */
    private static GuardrailProperties properties(boolean enabled, String... words) {
        GuardrailProperties properties = new GuardrailProperties();
        properties.setEnabled(enabled);
        properties.setSensitiveWords(new ArrayList<>(List.of(words)));
        return properties;
    }
}
