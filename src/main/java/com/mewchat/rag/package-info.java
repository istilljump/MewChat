/**
 * RAG 检索服务。
 *
 * <p>职责：把用户问题变成"可用的知识片段"，供 Agent 生成回答时引用。
 *
 * <p>规划的子包：
 * <ul>
 *     <li>{@code retrieval} —— 向量检索主流程（改写 → 召回 → 过滤）</li>
 *     <li>{@code document} —— 文档解析、切分、向量化、入库</li>
 *     <li>{@code rerank} —— 召回结果重排序</li>
 * </ul>
 *
 * <p>向量库（Milvus）的读写通过 {@code dao.milvus} 完成，本层不直接操作 SDK。
 */
package com.mewchat.rag;
