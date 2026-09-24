/**
 * 通用层：与具体业务无关的基础设施。
 *
 * <p>子包划分：
 * <ul>
 *     <li>{@code result} —— 统一响应包装 {@link com.mewchat.common.result.Result} 与错误码</li>
 *     <li>{@code exception} —— 业务异常与全局异常处理</li>
 *     <li>{@code constant} —— 常量与枚举</li>
 *     <li>{@code util} —— 工具类</li>
 * </ul>
 *
 * <p>本层可被任何层依赖，自身不反向依赖业务层。
 */
package com.mewchat.common;
