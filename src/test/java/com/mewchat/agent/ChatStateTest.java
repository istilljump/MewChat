package com.mewchat.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话状态机的结构测试（纯单元测试，不依赖 Spring 与数据库）。
 *
 * <p>状态机是整个编排的骨架，一旦转移表写漏或写错，表现为"某个分支永远走不到"
 * 或"请求卡在某个状态"，而且往往要等到特定输入才暴露。这里用图遍历的方式
 * 把这类结构性问题在单元测试阶段拦住。
 *
 * @author MewChat
 */
class ChatStateTest {

    /** 流程入口 */
    private static final ChatState ENTRY = ChatState.CONTEXT_LOAD;

    /**
     * 除终态外，每个状态都必须有后继，否则流程会卡死在这里。
     */
    @Test
    void everyNonTerminalStateShouldHaveSuccessor() {
        for (ChatState state : ChatState.values()) {
            if (state.isTerminal()) {
                continue;
            }
            assertThat(state.nextStates())
                    .as("状态 %s(%s) 没有任何后继状态，流程会在此卡死", state.name(), state.getLabel())
                    .isNotEmpty();
        }
    }

    /**
     * 终态不能再有后继，否则流程不会停止。
     */
    @Test
    void terminalStateShouldHaveNoSuccessor() {
        assertThat(ChatState.END.isTerminal()).isTrue();
        assertThat(ChatState.END.nextStates()).isEmpty();

        for (ChatState state : ChatState.values()) {
            if (state != ChatState.END) {
                assertThat(state.isTerminal())
                        .as("只有 END 应该是终态，%s 不是", state.name())
                        .isFalse();
            }
        }
    }

    /**
     * 所有状态都必须能从入口到达。
     *
     * <p>这条能抓出"新加了状态却忘了在转移表里接上"的问题 ——
     * 那种情况下该状态的节点实现了也永远不会被执行。
     */
    @Test
    void allStatesShouldBeReachableFromEntry() {
        Set<ChatState> reachable = traverseFrom(ENTRY);
        assertThat(reachable)
                .as("以下状态无法从入口到达，说明转移表漏配了：%s",
                        EnumSet.allOf(ChatState.class).stream()
                                .filter(s -> !reachable.contains(s)).toList())
                .containsExactlyInAnyOrder(ChatState.values());
    }

    /**
     * 从任何状态出发都必须能走到终态。
     *
     * <p>这条能抓出"某个分支走进死胡同"的问题，比死循环更隐蔽：
     * 死循环至少能被 maxSteps 拦住，死胡同则是流程停在一个非终态上不动。
     */
    @Test
    void everyStateShouldReachEnd() {
        for (ChatState state : ChatState.values()) {
            assertThat(traverseFrom(state))
                    .as("从状态 %s(%s) 出发无法到达 END，该分支走进了死胡同",
                            state.name(), state.getLabel())
                    .contains(ChatState.END);
        }
    }

    /**
     * 未声明的转移必须被拒绝。
     *
     * <p>调度器每次都调用本方法校验，所以它是否正确直接决定
     * "节点跳出错误状态"能不能被及时发现。
     */
    @Test
    void undeclaredTransitionsShouldBeRejected() {
        assertThat(ChatState.CONTEXT_LOAD.canTransitionTo(ChatState.REPLY)).isFalse();
        assertThat(ChatState.INTENT_RECOGNIZE.canTransitionTo(ChatState.RAG_RETRIEVE)).isFalse();
        assertThat(ChatState.TOOL_CALL.canTransitionTo(ChatState.REPLY)).isFalse();
        assertThat(ChatState.END.canTransitionTo(ChatState.REPLY)).isFalse();
        assertThat(ChatState.REPLY.canTransitionTo(null)).isFalse();

        // 护栏不能被绕过：入口只能先到护栏，护栏只能到"续接判断"或"拒答"
        assertThat(ChatState.CONTEXT_LOAD.canTransitionTo(ChatState.INTENT_RECOGNIZE))
                .as("上下文加载不能直达意图识别，否则违规内容会被送进大模型")
                .isFalse();
        assertThat(ChatState.GUARD_CHECK.canTransitionTo(ChatState.INTENT_RECOGNIZE)).isFalse();
        assertThat(ChatState.CONTEXT_LOAD.canTransitionTo(ChatState.GUARD_CHECK)).isTrue();
        assertThat(ChatState.GUARD_CHECK.canTransitionTo(ChatState.RESUME_CHECK)).isTrue();
        assertThat(ChatState.GUARD_CHECK.canTransitionTo(ChatState.REJECT)).isTrue();

        // 续接的两个出口：命中候选就直奔工具层（跳过意图识别与路由），否则按新问题走
        assertThat(ChatState.RESUME_CHECK.canTransitionTo(ChatState.INTENT_RECOGNIZE)).isTrue();
        assertThat(ChatState.RESUME_CHECK.canTransitionTo(ChatState.TOOL_CALL)).isTrue();
        assertThat(ChatState.RESUME_CHECK.canTransitionTo(ChatState.ROUTE)).isFalse();

        // 已声明的主要路径都要放行
        assertThat(ChatState.ROUTE.canTransitionTo(ChatState.RAG_RETRIEVE)).isTrue();
        assertThat(ChatState.ROUTE.canTransitionTo(ChatState.TOOL_CALL)).isTrue();
        assertThat(ChatState.CONFIDENCE_CHECK.canTransitionTo(ChatState.REPLY)).isTrue();
        assertThat(ChatState.CONFIDENCE_CHECK.canTransitionTo(ChatState.FALLBACK)).isTrue();
        assertThat(ChatState.CONFIDENCE_CHECK.canTransitionTo(ChatState.CLARIFY)).isTrue();
        // 中档置信度要能回到检索，做一次放宽条件的补充检索
        assertThat(ChatState.CONFIDENCE_CHECK.canTransitionTo(ChatState.RAG_RETRIEVE)).isTrue();
        // 拒答是独立的终点：它不是"答不上来"，不能与兜底合并
        assertThat(ChatState.REJECT.canTransitionTo(ChatState.END)).isTrue();
        // 回复生成失败可降级为兜底
        assertThat(ChatState.REPLY.canTransitionTo(ChatState.FALLBACK)).isTrue();
    }

    /**
     * 意图到路由目标的映射必须落在 ROUTE 的合法后继里。
     *
     * <p>意图枚举与状态转移表是两处独立配置，这条断言把它们绑在一起：
     * 新增意图时若写了个 ROUTE 到不了的目标，会被立刻发现。
     */
    @Test
    void intentRouteTargetsShouldBeLegalRouteSuccessors() {
        for (IntentType intent : IntentType.values()) {
            assertThat(ChatState.ROUTE.canTransitionTo(intent.getRouteTarget()))
                    .as("意图 %s 的路由目标 %s 不是 ROUTE 的合法后继",
                            intent.name(), intent.getRouteTarget())
                    .isTrue();
        }
    }

    /**
     * 意图解析对非法输入必须降级而不是抛异常。
     */
    @Test
    void intentParseShouldDegradeToUnknown() {
        assertThat(IntentType.parse(null)).isEqualTo(IntentType.UNKNOWN);
        assertThat(IntentType.parse("")).isEqualTo(IntentType.UNKNOWN);
        assertThat(IntentType.parse("不存在的意图")).isEqualTo(IntentType.UNKNOWN);
        // 大小写与空格应当被容忍
        assertThat(IntentType.parse(" order_query ")).isEqualTo(IntentType.ORDER_QUERY);
        assertThat(IntentType.parse("Complaint")).isEqualTo(IntentType.COMPLAINT);
    }

    /**
     * 从指定状态出发做广度优先遍历，返回所有可达状态（含起点）。
     *
     * @param start 起点
     * @return 可达状态集合
     */
    private Set<ChatState> traverseFrom(ChatState start) {
        Set<ChatState> visited = EnumSet.of(start);
        Deque<ChatState> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            ChatState current = queue.poll();
            for (ChatState next : current.nextStates()) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }
}
