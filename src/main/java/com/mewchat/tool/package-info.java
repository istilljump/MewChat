/**
 * 业务工具集。
 *
 * <p>职责：把确定性的业务查询能力（订单、物流、退款政策）暴露给 Agent。
 * 每个工具都要提供清晰的名称、用途描述与参数说明 ——
 * 名称供编排层查表调用，描述与参数结构由 {@code @Tool} 注解承载，
 * 供将来模型自主选择工具时生成工具描述。
 *
 * <p>子包：
 * <ul>
 *     <li>{@code order} —— 订单查询</li>
 *     <li>{@code logistics} —— 物流查询</li>
 *     <li>{@code refund} —— 退款政策查询（按商品类目）</li>
 *     <li>{@code product} —— 商品查询：阶段 6 的需求清单里未列出，<b>尚未实现</b></li>
 * </ul>
 *
 * <p>分层约定：
 * <ul>
 *     <li>工具本身不写 SQL、不直接访问 {@code dao}；接入真实业务系统时，
 *         取数封装到 {@code service} 层，工具改为注入该服务</li>
 *     <li>编排层唯一的入口是 {@link com.mewchat.tool.ToolInvoker}，
 *         由 {@link com.mewchat.tool.BusinessToolInvoker} 按工具名分发，
 *         因此编排层不认识任何具体工具类</li>
 *     <li>新增一个业务能力 = 写一个 {@link com.mewchat.tool.BusinessTool} 实现并标
 *         {@code @Component}，编排层无需改动。工具名重复、或 {@code @Tool} 注解与
 *         工具名不一致，都会在应用启动时直接报错，不会拖到运行期才发现</li>
 *     <li>工具不得抛异常、不得返回 null：失败用 {@link com.mewchat.tool.ToolResult#fail}，
 *         信息不足用 {@link com.mewchat.tool.ToolResult#needMoreInfo}，
 *         记录不存在用 {@link com.mewchat.tool.ToolResult#notFound}。
 *         后两者都导向"追问用户"，而不是让一次输入错误变成一次转人工</li>
 * </ul>
 */
package com.mewchat.tool;
