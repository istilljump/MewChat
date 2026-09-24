package com.mewchat.agent;

/**
 * 流式输出回调 —— 编排层把"进度"与"文字"实时交出去的出口。
 *
 * <p><b>为什么放在 {@code agent} 而不是 {@code api}</b>：编排层需要往外推片段，
 * 若回调接口定义在 api 层，就形成了 {@code agent → api} 的反向依赖，
 * 破坏本项目的单向分层。接口放这里，由 api 层去实现它，依赖方向是 api → agent，
 * 与整体分层一致。编排层因此完全不认识 HTTP、SSE，只认识"往这里写一个字"。
 *
 * <p><b>只有这一处会在模型回调线程上被调用</b>：回复生成走的是流式模型，
 * 片段是在模型客户端的 IO 线程里回调过来的，而状态机的推进发生在请求线程上。
 * 也就是说，{@link #onFragment} 与 {@link #onState} 可能来自不同线程，
 * 实现方必须自己保证线程安全（SSE 的实现依赖 {@code SseEmitter} 内部同步，
 * 见 {@code api.chat.SseStreamListener}）。
 *
 * <p>两个方法都给了默认空实现：非流式场景不需要任何实现，
 * 测试里也可以只覆写关心的那一个。
 *
 * @author MewChat
 */
public interface ChatStreamListener {

    /**
     * 流程进入某个状态。
     *
     * <p>推送状态是为了让前端能显示"正在查询订单…"这类进度提示。
     * 一次对话会按顺序触发多次，最后一个是终态（{@code END}）。
     *
     * @param state 刚进入的状态
     */
    default void onState(ChatState state) {
    }

    /**
     * 回复生成过程中的一个文本片段。
     *
     * <p>片段的切分由模型决定（通常几个字一片），拼接起来才是完整回复。
     * 只有走了回复分支才会有片段：追问与兜底的话术是固定文本，
     * 一次性给全，不逐字输出。
     *
     * @param fragment 文本片段，非空
     */
    default void onFragment(String fragment) {
    }
}
