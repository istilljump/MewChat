package com.mewchat.api.chat;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import com.mewchat.agent.ChatStreamListener;
import com.mewchat.common.constant.ChatConstants;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把编排层的流式回调转成 SSE 事件流。
 *
 * <p><b>事件约定</b>（每帧的 data 都是 {@link Result} 的 JSON，与其它接口一致，
 * 前端可以用同一套解析逻辑）：
 * <table border="1">
 *     <caption>SSE 事件</caption>
 *     <tr><th>事件名</th><th>data</th><th>说明</th></tr>
 *     <tr><td>{@code session}</td><td>{@code Result<String>}</td><td>会话ID，首帧下发</td></tr>
 *     <tr><td>{@code state}</td><td>{@code Result<String>}</td><td>流程状态中文名，进度提示用</td></tr>
 *     <tr><td>{@code message}</td><td>{@code Result<String>}</td><td>回复文本片段，追加到气泡</td></tr>
 *     <tr><td>{@code done}</td><td>{@code Result<ChatReply>}</td><td>本轮权威结果，含引用来源、置信度、是否转人工</td></tr>
 *     <tr><td>{@code error}</td><td>{@code Result<Void>}</td><td>失败原因，收到后应停止等待</td></tr>
 * </table>
 *
 * <p><b>{@code done} 必须被前端当作权威结果</b>：流式过程中可能已经推送了半句话，
 * 但只有 {@code done} 里的 {@code content} 是完整且最终的内容 ——
 * 例如推了几个字之后模型失败，流程会降级为兜底话术，此时屏幕上那半句话是作废的。
 * 前端应当<b>替换</b>而不是追加。
 *
 * <p><b>本类不得抛出异常</b>（{@link ChatStreamListener} 的约定）：
 * 客户端随时可能关页面，那一刻的推送失败是正常现象而不是故障。
 * 一旦抛出，异常会穿透编排层、让一次正常的对话被记成流程异常。
 * 因此所有发送都吞掉异常并标记连接失效，后续推送直接短路。
 *
 * <p><b>线程安全</b>：片段来自模型客户端的 IO 线程，会话/结束事件来自流式任务线程，
 * 两者可能并发。所有发送都在同一把锁内完成，{@code SseEmitter} 本身也不保证并发调用安全。
 *
 * @author MewChat
 */
public class SseStreamListener implements ChatStreamListener {

    private static final Logger log = LoggerFactory.getLogger(SseStreamListener.class);

    private final SseEmitter emitter;

    /** 保护 connected / fragmentDelivered，并串行化所有发送 */
    private final Object sendLock = new Object();

    /** 是否已推送过文本片段 */
    private boolean fragmentDelivered;

    /**
     * 已经作为片段推送出去的正文。
     *
     * <p>收尾时用它判断"权威结果里还有哪部分没送达"。多意图轮里这个字段
     * 显出必要性：第一段流式推送了，第二段（追问/兜底的固定话术）不经过模型、
     * 一个片段都不会有 —— 若只看 {@link #fragmentDelivered}（推没推过），
     * 第二段就永远到不了客户端的气泡里，要等 done 替换才现身。
     */
    private final StringBuilder streamedText = new StringBuilder();

    /** 连接是否已失效：客户端断开，或本轮已正常结束 */
    private boolean closed;

    /**
     * 是否已经收过尾（{@code complete()} 只允许调一次）。
     *
     * <p>与 {@link #closed} 分开是必须的：两者含义不同 —— {@code closed} 表示
     * "不要再用这个连接推送"，{@code finished} 表示"已经归还过异步请求"。
     * 客户端断开时 {@code closed} 会先变成 true，若收尾也只看 {@code closed}，
     * 那条路径就永远不会调用 {@code complete()}，容器的异步请求会一直挂到超时。
     */
    private boolean finished;

    public SseStreamListener(SseEmitter emitter) {
        this.emitter = emitter;
        // 容器的三个生命周期回调都归约为"连接失效"：客户端断开/停止时，
        // 若本轮还没有任何推送（比如还在意图识别），send 不会被触发、closed 无从置位，
        // 取消信号就会漏掉 —— 这三个回调补上了"没有推送也能感知断开"的缺口
        emitter.onCompletion(() -> markClosed());
        emitter.onTimeout(this::markClosed);
        emitter.onError(e -> markClosed());
    }

    /**
     * 标记连接失效（供容器的生命周期回调调用）。
     */
    private void markClosed() {
        synchronized (sendLock) {
            closed = true;
        }
    }

    /**
     * 用户是否已离开。
     *
     * <p>{@code closed} 同时覆盖"推送失败感知到的断开"与"容器回调感知到的断开"，
     * 而 finish() 之后的流程早已结束、不会再有人查询取消信号 —— 因此 closed 即"已取消"。
     */
    @Override
    public boolean isCancelled() {
        synchronized (sendLock) {
            return closed;
        }
    }

    /**
     * 下发会话ID。
     *
     * @param sessionId 会话业务ID
     */
    public void start(String sessionId) {
        send(ChatConstants.SSE_EVENT_SESSION, Result.success(sessionId));
    }

    @Override
    public void onState(ChatState state) {
        send(ChatConstants.SSE_EVENT_STATE, Result.success(state.getLabel()));
    }

    @Override
    public void onFragment(String fragment) {
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            fragmentDelivered = true;
            streamedText.append(fragment);
        }
        send(ChatConstants.SSE_EVENT_MESSAGE, Result.success(fragment));
    }

    /**
     * 结束本轮：补齐文本、下发权威结果、关闭连接。
     *
     * <p>这一步保证"无论回答走哪条分支，前端都能拿到完整正文"：
     * 追问与兜底的话术是固定文本、根本不经过模型，因此一个片段都不会有，
     * 若只发 {@code done}，前端就会渲染出一个空气泡。
     *
     * <p>补齐的口径是<b>"没送达的部分"</b>而不是"整段或没有"：
     * 一段都没送达（固定话术轮）就补整段；送达了一部分（多意图轮先流式了
     * 第一段、第二段是固定话术）就只补剩余的后缀 —— 前端按片段追加气泡，
     * 后缀追加后恰好拼出完整正文。只有当权威内容与已推送部分不是
     * 前缀关系（生成失败后换成兜底话术等）时才不补：此时推后缀会拼出乱序文本，
     * 由 {@code done} 的权威替换兜底（见类注释的替换约定）。
     *
     * @param reply 本轮结果
     */
    public void complete(ChatReply reply) {
        String undelivered = null;
        synchronized (sendLock) {
            if (!closed && StringUtils.hasText(reply.getContent())) {
                String content = reply.getContent();
                String streamed = streamedText.toString();
                if (streamed.isEmpty()) {
                    undelivered = content;
                } else if (content.length() > streamed.length() && content.startsWith(streamed)) {
                    undelivered = content.substring(streamed.length());
                }
            }
        }
        if (undelivered != null) {
            send(ChatConstants.SSE_EVENT_MESSAGE, Result.success(undelivered));
        }
        send(ChatConstants.SSE_EVENT_DONE, Result.success(reply));
        finish();
    }

    /**
     * 本轮失败：下发错误事件并关闭连接。
     *
     * <p>取原始错误码而不是 {@link ResultCode} 枚举：{@code BizException} 携带的
     * 就是 int 码，若这里只收枚举，调用方就得把业务异常的错误码"翻译"成某个枚举，
     * 结果是前端收到的错误码与真实原因不一致。
     *
     * @param code    业务错误码
     * @param message 面向用户的提示
     */
    public void fail(int code, String message) {
        send(ChatConstants.SSE_EVENT_ERROR, Result.error(code, message));
        finish();
    }

    /**
     * 发送一帧 SSE 事件。
     *
     * @param event 事件名
     * @param data  事件数据，会被序列化为 JSON
     */
    private void send(String event, Object data) {
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 客户端断开（关页面、刷新、切走）会让 send 抛 IOException，这是正常现象。
                // 标记失效后不再推送，避免为后续每个片段都记一条错误日志。
                // 注意此时流程不会中断：回答会跑完并落库，用户刷新后能在历史里看到完整回复。
                // 真正的"取消生成"（省下模型 token）需要把中断信号传回编排层，属于后续优化
                closed = true;
                log.debug("SSE 连接已失效，停止推送：event={} 原因={}", event, e.toString());
            }
        }
    }

    /**
     * 正常或异常地结束这次异步请求。
     *
     * <p>必须调用 {@code complete()}：不结束的话，容器的异步请求会一直挂到超时，
     * 期间占用一个连接与一个异步上下文。连接早已失效时 {@code complete()} 也会抛异常，
     * 一并吞掉 —— 目的只是"尽量把资源还回去"。
     */
    private void finish() {
        synchronized (sendLock) {
            // 只判 finished：连接失效（closed）时同样必须调用 complete()，
            // 否则断连的这次请求会一直占用异步上下文与连接，直到超时
            if (finished) {
                return;
            }
            finished = true;
            closed = true;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("结束 SSE 连接时异常（可忽略）：{}", e.toString());
        }
    }
}
