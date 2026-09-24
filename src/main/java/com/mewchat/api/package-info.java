/**
 * 对外接口层。
 *
 * <p>职责：接收 HTTP 请求、参数校验（{@code @Valid}）、调用下层、用
 * {@link com.mewchat.common.result.Result} 包装响应。
 *
 * <p>规划的子包：
 * <ul>
 *     <li>{@code chat} —— 对话接口，含 SSE 流式输出</li>
 *     <li>{@code admin} —— 管理端接口（知识库、商品/订单维护）</li>
 *     <li>{@code auth} —— 登录鉴权</li>
 * </ul>
 *
 * <p><b>禁止</b>：在本层写业务逻辑、直接调用 {@code dao}。
 */
package com.mewchat.api;
