/**
 * Agent 编排核心。
 *
 * <p>职责：决定"用户这句话该交给谁处理、需要哪些信息、怎么组织回答"。
 *
 * <p>规划的子包：
 * <ul>
 *     <li>{@code supervisor} —— 主控 Agent，负责意图识别与任务分派</li>
 *     <li>{@code specialist} —— 专项 Agent（售后、导购、订单、物流等）</li>
 *     <li>{@code memory} —— 会话记忆（短期上下文 + 长期记忆读写）</li>
 * </ul>
 *
 * <p><b>禁止</b>：直接访问 {@code dao}；需要数据一律通过 {@code service} 或 {@code tool}。
 */
package com.mewchat.agent;
