package com.mewchat.tool;

import com.mewchat.tool.logistics.LogisticsTool;
import com.mewchat.tool.order.OrderTool;
import com.mewchat.tool.refund.RefundTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具注册表的测试（不依赖数据库与外部服务）。
 *
 * <p><b>为什么用真实的 Spring 容器而不是手搓一个 {@code ObjectProvider} 替身</b>：
 * 本类要验证的恰恰是"工具被 Spring 收集到"这件事本身 —— 自动收集、工具名唯一、
 * 启动校验，全都发生在容器装配阶段。手搓替身等于把被测对象换成了自己的假设，
 * 测出来的是"我以为容器会这么装配"。
 * 这里用一个只注册工具类的最小上下文，不加载 Boot 自动配置、不连库，代价很低。
 *
 * @author MewChat
 */
class BusinessToolInvokerTest {

    /**
     * 三个业务工具都应被发现，且能按工具名分发到正确的实现。
     */
    @Test
    void shouldDiscoverAllBusinessToolsAndDispatchByName() {
        try (AnnotationConfigApplicationContext context = toolContext()) {
            ToolInvoker invoker = context.getBean(ToolInvoker.class);

            assertThat(((BusinessToolInvoker) invoker).registeredToolNames())
                    .containsExactlyInAnyOrder(OrderTool.NAME, LogisticsTool.NAME, RefundTool.NAME);

            // 分发正确性：同一个入参交给不同工具，应得到各自的结果
            assertThat(invoker.invoke(OrderTool.NAME, Map.of("orderNo", "MC202409240001")).getSummary())
                    .contains("无线蓝牙耳机 Pro");
            assertThat(invoker.invoke(LogisticsTool.NAME, Map.of("trackingNo", "SF1234567890")).getSummary())
                    .contains("运输中");
            assertThat(invoker.invoke(RefundTool.NAME, Map.of("category", "生鲜")).getSummary())
                    .contains("不支持无理由退货");
        }
    }

    /**
     * 调用未注册的工具名要返回结构化失败，而不是抛异常或返回 null。
     */
    @Test
    void unknownToolNameShouldReturnStructuredFailure() {
        try (AnnotationConfigApplicationContext context = toolContext()) {
            ToolInvoker invoker = context.getBean(ToolInvoker.class);

            ToolResult result = invoker.invoke("not_a_tool", Map.of());

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("not_a_tool");
        }
    }

    /**
     * 工具名为空时返回失败而不是让流程拿到 null。
     */
    @Test
    void blankToolNameShouldReturnStructuredFailure() {
        try (AnnotationConfigApplicationContext context = toolContext()) {
            ToolInvoker invoker = context.getBean(ToolInvoker.class);

            assertThat(invoker.invoke(null, Map.of()).isSuccess()).isFalse();
            assertThat(invoker.invoke("  ", Map.of()).isSuccess()).isFalse();
        }
    }

    /**
     * 工具名重复必须在启动时报错。
     *
     * <p>重名的后果是其中一个工具被静默覆盖：调用方按名字调用，
     * 拿到的却是另一个工具的结果，而且没有任何报错 —— 这种错必须拦在启动阶段。
     */
    @Test
    void duplicateToolNameShouldFailAtStartup() {
        // 必须把真正的 OrderTool 也注册进来，否则只有一个工具、谈不上重名
        assertStartupFailureContaining("工具名重复", OrderTool.class, DuplicateOrderTool.class);
    }

    /**
     * 缺少与工具名一致的 {@code @Tool} 注解必须在启动时报错。
     *
     * <p>确定性调用路径不看注解，因此漏标注解在运行期毫无症状 ——
     * 直到有人发现"模型从来不调用这个工具"才会怀疑到它，
     * 那时已经很难归因了。所以这里要求启动即失败。
     */
    @Test
    void missingToolAnnotationShouldFailAtStartup() {
        assertStartupFailureContaining("@Tool", UnannotatedTool.class);
    }

    /**
     * 注解名与 {@link BusinessTool#name()} 不一致同样要报错 ——
     * 两者对不上时，模型看到的工具名与编排层调用的工具名是两个不同的东西。
     */
    @Test
    void mismatchedToolAnnotationNameShouldFailAtStartup() {
        assertStartupFailureContaining("@Tool", MismatchedAnnotationTool.class);
    }

    /**
     * 断言容器在装配阶段因指定原因启动失败。
     *
     * <p>不直接断言异常消息：Spring 会把构造器抛出的异常包成
     * {@code BeanCreationException}，消息里只有一句"Constructor threw exception"，
     * 真正的原因在 cause 链里。断言根因才验到我们抛的那句。
     *
     * @param expectedText 期望出现在根因消息里的文本
     * @param toolClasses  要注册的工具类，其中至少一个会触发校验失败
     */
    private void assertStartupFailureContaining(String expectedText, Class<?>... toolClasses) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            for (Class<?> toolClass : toolClasses) {
                context.register(toolClass);
            }
            context.register(BusinessToolInvoker.class);

            assertThatThrownBy(context::refresh)
                    .as("工具校验不通过时应启动失败，而不是把问题带到运行期")
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining(expectedText);
        }
    }

    /**
     * 构造只含业务工具的最小 Spring 上下文。
     *
     * @return 已刷新、可直接取 Bean 的上下文
     */
    private AnnotationConfigApplicationContext toolContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(OrderTool.class, LogisticsTool.class, RefundTool.class, BusinessToolInvoker.class);
        context.refresh();
        return context;
    }

    /* ==================== 仅用于校验逻辑的测试用工具 ==================== */

    /**
     * 与 {@link OrderTool} 同名的工具，用于验证重名会被拦下。
     */
    static class DuplicateOrderTool implements BusinessTool {

        @Override
        public String name() {
            return OrderTool.NAME;
        }

        @Override
        public ToolResult invoke(Map<String, Object> params) {
            return ToolResult.ok(name(), "", Map.of());
        }

        /**
         * 注解与名字一致，确保本用例失败的原因是"重名"而不是"注解缺失"。
         *
         * @param orderNo 订单号
         * @return 固定为空串
         */
        @Tool(name = OrderTool.NAME, value = "重复的工具名")
        public String duplicated(@P("订单号") String orderNo) {
            return "";
        }
    }

    /**
     * 没有 {@code @Tool} 方法的工具。
     */
    static class UnannotatedTool implements BusinessTool {

        @Override
        public String name() {
            return "unannotated_tool";
        }

        @Override
        public ToolResult invoke(Map<String, Object> params) {
            return ToolResult.ok(name(), "", Map.of());
        }
    }

    /**
     * 注解名与 {@link #name()} 不一致的工具。
     */
    static class MismatchedAnnotationTool implements BusinessTool {

        @Override
        public String name() {
            return "declared_name";
        }

        @Override
        public ToolResult invoke(Map<String, Object> params) {
            return ToolResult.ok(name(), "", Map.of());
        }

        /**
         * 注解名故意与 {@link #name()} 不同。
         *
         * @param orderNo 订单号
         * @return 固定为空串
         */
        @Tool(name = "some_other_name", value = "注解名与实现声明的工具名不一致")
        public String mismatched(@P("订单号") String orderNo) {
            return "";
        }
    }
}
