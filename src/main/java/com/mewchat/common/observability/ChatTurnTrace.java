package com.mewchat.common.observability;

import java.math.BigDecimal;

/**
 * 一轮对话的可观测摘要。
 *
 * <p>它是编排层与上报实现之间的边界对象，因此<b>字段全是基本类型与字符串</b>：
 * 若这里出现 {@code IntentType} 之类的业务枚举，{@code common} 就会反向依赖 {@code agent}，
 * 破坏"common 不依赖业务层"的约定；而且上报出去的契约也不该跟着内部枚举改名而抖动。
 *
 * @param sessionId       会话业务ID，用于把同一段对话的若干轮串起来
 * @param userId          用户ID，可为空
 * @param question        用户问题
 * @param answer          本轮回答
 * @param intent          识别出的意图名
 * @param finalState      本轮终态（REPLY / CLARIFY / FALLBACK / REJECT）
 * @param handlerAgent    处理该问题的专家节点名
 * @param toolName        调用的工具名，未走工具时为空
 * @param toolSuccess     工具调用是否成功，未走工具时为空
 * @param retrievalHits   检索命中的知识片段数
 * @param confidence      本轮有效置信度
 * @param costMs          本轮耗时（毫秒）
 * @param totalTokens     总 token 数
 * @param modelName       使用的模型名
 * @author MewChat
 */
public record ChatTurnTrace(String sessionId,
                            Long userId,
                            String question,
                            String answer,
                            String intent,
                            String finalState,
                            String handlerAgent,
                            String toolName,
                            Boolean toolSuccess,
                            int retrievalHits,
                            BigDecimal confidence,
                            Long costMs,
                            Integer totalTokens,
                            String modelName) {
}
