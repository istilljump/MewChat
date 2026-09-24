package com.mewchat.tool;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务工具注册表：编排层看到的工具调用入口。
 *
 * <p>把 Spring 容器里所有 {@link BusinessTool} 收集成"工具名 → 实现"的表，
 * 调用时一次查表分发。
 *
 * <p><b>为什么用"自动收集 + 查表"而不是在 {@link #invoke} 里写 switch</b>：
 * switch 需要手工维护一份"工具名 → 分支"的清单，而漏维护的表现是
 * <b>静默的</b> —— 工具类写好了、注解也标了，却在 switch 里少一个分支，
 * 于是线上报"未注册的工具"。自动收集让"新增工具"只需给实现类加 {@code @Component}，
 * 漏注册在结构上不可能发生。这与本项目的取向一致：把配置与代码绑在一起，
 * 而不是靠人记住两处要同步改。
 *
 * <p><b>启动即校验两件事</b>：
 * <ol>
 *     <li>工具名不重复。重名会让其中一个工具被静默覆盖，调用方拿到的是另一个工具的结果</li>
 *     <li>每个工具都声明了 {@code @Tool(name = ...)} 且与 {@link BusinessTool#name()} 一致。
 *         确定性调用路径不看注解，但模型自主选择工具时靠注解生成工具描述 ——
 *         注解漏标或名字写歪，这个工具对模型就是不可见的，而问题要到"模型从不调用它"
 *         才会被发现，极难归因</li>
 * </ol>
 *
 * @author MewChat
 */
@Component
public class BusinessToolInvoker implements ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(BusinessToolInvoker.class);

    /** 工具名 → 实现，启动后不可变 */
    private final Map<String, BusinessTool> toolMap;

    public BusinessToolInvoker(ObjectProvider<BusinessTool> toolProvider) {
        this.toolMap = buildToolMap(toolProvider);
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> params) {
        if (!StringUtils.hasText(toolName)) {
            return ToolResult.fail(toolName, "工具名不能为空");
        }

        BusinessTool tool = toolMap.get(toolName);
        if (tool == null) {
            log.error("调用了未注册的工具：tool={} 已注册={}", toolName, toolMap.keySet());
            return ToolResult.fail(toolName, "未注册的工具：" + toolName);
        }

        try {
            ToolResult result = tool.invoke(params == null ? Map.of() : params);
            if (result == null) {
                // 契约要求不返回 null。真出现了也必须转成结构化失败，
                // 否则 NPE 会一路冒泡到编排层变成一次兜底，丢失"是哪个工具坏了"的信息
                log.error("工具返回 null，违反 BusinessTool 契约：tool={}", toolName);
                return ToolResult.fail(toolName, "工具未返回结果");
            }
            return result;
        } catch (Exception e) {
            // 契约同样要求工具不抛异常。这里兜一层是为了防止某个工具的实现缺陷
            // 把"一次查询失败"放大成"整轮对话异常"
            log.error("工具执行异常：tool={}", toolName, e);
            return ToolResult.fail(toolName, "工具执行异常：" + e.getMessage());
        }
    }

    /**
     * 已注册的工具名。
     *
     * <p>供启动日志、健康检查与测试断言使用。
     *
     * @return 工具名集合
     */
    public Set<String> registeredToolNames() {
        return toolMap.keySet();
    }

    /**
     * 已注册的工具实现。
     *
     * @return 工具列表
     */
    public List<BusinessTool> registeredTools() {
        return List.copyOf(toolMap.values());
    }

    /**
     * 取某个工具提供的候选选项，供澄清追问使用。
     *
     * <p>任何失败都退化成"没有候选"，不抛异常：候选只是让追问更好用的锦上添花，
     * 拿不到就退回干问一句"请提供订单号"，绝不该因为列候选失败而让整轮对话失败。
     *
     * @param toolName 工具名，可为 null
     * @param userId   当前登录用户ID
     * @return 候选项列表，无候选或取不到时为空列表
     */
    public List<ClarificationOption> listOptions(String toolName, Long userId) {
        if (!StringUtils.hasText(toolName)) {
            return List.of();
        }
        BusinessTool tool = toolMap.get(toolName);
        if (tool == null) {
            return List.of();
        }
        try {
            List<ClarificationOption> options = tool.listOptions(userId);
            return options == null ? List.of() : options;
        } catch (Exception e) {
            log.warn("取候选选项失败，退化为纯文字追问：tool={}", toolName, e);
            return List.of();
        }
    }

    /**
     * 收集并校验工具。
     *
     * @param toolProvider Spring 提供的全部工具实现
     * @return 不可变的工具名 → 实现映射
     * @throws IllegalStateException 工具名重复或注解校验不通过时抛出
     */
    @Override
    public String clarificationParam(String toolName) {
        BusinessTool tool = toolMap.get(toolName);
        return tool == null ? null : tool.clarificationParam();
    }

    private Map<String, BusinessTool> buildToolMap(ObjectProvider<BusinessTool> toolProvider) {
        Map<String, BusinessTool> map = new LinkedHashMap<>();
        toolProvider.orderedStream().forEach(tool -> {
            validateToolAnnotation(tool);
            BusinessTool previous = map.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名重复：" + tool.name() + " 同时由 "
                        + previous.getClass().getName() + " 与 " + tool.getClass().getName()
                        + " 声明。工具名是编排层唯一的调用依据，重名会导致其中一个永远调不到");
            }
        });

        if (map.isEmpty()) {
            // 不是启动失败：没有工具时订单/物流类问题会走兜底转人工，对话本身仍可用
            log.warn("没有注册任何业务工具，订单与物流类问题将无法回答");
        } else {
            log.info("业务工具注册完成，共 {} 个：{}（候选参数：{}）", map.size(), map.keySet(),
                    map.values().stream()
                            .filter(tool -> tool.clarificationParam() != null)
                            .map(tool -> tool.name() + "->" + tool.clarificationParam())
                            .collect(Collectors.joining(", ")));
            // 工具名与方法的对应关系只在 debug 下打：排查"模型为什么不调用某个工具"时，
            // 第一件事就是确认注解到底标在了哪个方法上
            log.debug("工具与方法的对应关系：\n{}", describeTools(map.values()));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 校验工具声明了与 {@link BusinessTool#name()} 一致的 {@code @Tool} 方法。
     *
     * @param tool 工具实现
     * @throws IllegalStateException 校验不通过时抛出
     */
    private void validateToolAnnotation(BusinessTool tool) {
        boolean declared = Arrays.stream(tool.getClass().getMethods())
                .map(method -> method.getAnnotation(Tool.class))
                .filter(java.util.Objects::nonNull)
                .anyMatch(annotation -> tool.name().equals(annotation.name()));

        if (!declared) {
            throw new IllegalStateException("工具 " + tool.getClass().getName()
                    + " 没有声明 @Tool(name = \"" + tool.name() + "\") 的方法。"
                    + "确定性调用路径虽然不看注解，但模型自主调用路径要靠注解生成工具描述，"
                    + "缺少它这个工具对模型不可见");
        }
    }

    /**
     * 列出每个工具名对应的实现方法。
     *
     * <p><b>必须是静态方法并接收工具集合，不能读 {@link #toolMap} 字段</b>：
     * 本方法在构造器里被调用，那一刻 {@code toolMap} 还没被赋值（仍在计算中），
     * 读字段会拿到 null 并抛 NPE —— 而且是在启动阶段抛，表现为应用起不来。
     *
     * @param tools 工具实现集合
     * @return 形如 {@code order_query -> OrderTool#queryOrder} 的多行文本
     */
    private static String describeTools(Collection<BusinessTool> tools) {
        StringBuilder builder = new StringBuilder();
        for (BusinessTool tool : tools) {
            for (Method method : tool.getClass().getMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null && tool.name().equals(annotation.name())) {
                    builder.append(tool.name())
                            .append(" -> ")
                            .append(tool.getClass().getSimpleName())
                            .append('#')
                            .append(method.getName())
                            .append('\n');
                }
            }
        }
        return builder.toString();
    }
}
