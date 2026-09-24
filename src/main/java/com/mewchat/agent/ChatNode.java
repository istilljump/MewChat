package com.mewchat.agent;

/**
 * 状态机节点。
 *
 * <p>每个状态对应一个实现类，节点只做两件事：把本状态该做的计算做完（写进上下文），
 * 然后<b>显式返回下一个状态</b>。流程走向由返回值决定并接受
 * {@link ChatState#canTransitionTo(ChatState)} 校验，
 * 不由大模型或隐式副作用决定。
 *
 * <p>实现类由 Spring 装配为 Bean，{@code ChatSupervisor} 会按
 * {@link #state()} 收集成状态 → 节点的映射。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *     <li>节点内不做持久化，持久化由调度器在流程首尾统一处理，边界清晰</li>
 *     <li>节点失败不要吞异常：需要降级时应显式返回 FALLBACK 或 CLARIFY，
 *         让"降级"成为可见的流程决策而不是静默兜底</li>
 * </ul>
 *
 * @author MewChat
 */
public interface ChatNode {

    /**
     * 本节点负责的状态。
     *
     * @return 状态枚举
     */
    ChatState state();

    /**
     * 执行本状态的逻辑。
     *
     * @param context 对话上下文，节点在其中读取输入、写入结果
     * @return 下一个状态，必须是 {@link ChatState#nextStates()} 中声明的值之一
     */
    ChatState execute(ChatContext context);
}
