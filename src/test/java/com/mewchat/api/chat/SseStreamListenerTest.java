package com.mewchat.api.chat;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * SSE 监听器的收尾行为测试（纯单元测试，只依赖一个替身 {@link SseEmitter}）。
 *
 * <p><b>为什么单独验"客户端断开之后有没有 complete()"</b>：
 * "断开后不再推送"是容易做对的部分，难做对的是<b>把异步请求还回去</b>。
 * 若收尾逻辑因为"连接已经失效"就提前返回，这个请求会一直占用容器的异步上下文
 * 与一条连接，直到 {@code mewchat.sse.timeout-ms}（默认 5 分钟）才被回收。
 * 现象是"用户关掉页面后服务端资源迟迟不释放"，并发一上来就可能打满容器线程，
 * 而接口测试看不出来 —— 它只关心推了什么事件，不关心有没有放手。
 *
 * @author MewChat
 */
class SseStreamListenerTest {

    /**
     * 推送失败（客户端已断开）之后，收尾仍然必须调用 {@code complete()}。
     */
    @Test
    void finishShouldCompleteEmitterEvenAfterSendFailure() throws Exception {
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        // 模拟客户端关页面：从第一次 send 起就抛 IOException。
        // send 是 void 方法，只能用 willThrow().given() 这种形式打桩
        willThrow(new IOException("Broken pipe"))
                .given(emitter).send(any(SseEmitter.SseEventBuilder.class));

        SseStreamListener listener = new SseStreamListener(emitter);

        assertThatCode(() -> {
            listener.start("session-1");
            listener.onFragment("半句话");
            listener.complete(ChatReply.builder().content("完整回答").build());
        }).doesNotThrowAnyException();

        // 关键断言：连接失效不等于"可以不管了"，异步请求仍要归还
        verify(emitter, times(1)).complete();
    }

    /**
     * 收尾只能做一次：重复收尾既不抛异常，也不把已经归还的异步请求再结束一遍。
     */
    @Test
    void finishShouldBeIdempotent() throws Exception {
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        SseStreamListener listener = new SseStreamListener(emitter);

        listener.start("session-2");
        listener.onState(ChatState.REPLY);
        listener.onFragment("回答");
        listener.complete(ChatReply.builder().content("回答").build());
        // 再收一次尾：不应再动 emitter
        listener.fail(10001, "重复收尾");

        verify(emitter, times(1)).complete();
        // 4 次推送：session / state / message(片段) / done，加上一次 complete，没有多余动作。
        // 已经推过片段时收尾不再补发完整文本，所以这里是 4 而不是 5
        verify(emitter, times(4)).send(any(SseEmitter.SseEventBuilder.class));
        // 构造器注册的三个容器生命周期回调（取消信号感知的缺口）也是合法交互
        verify(emitter).onCompletion(any(Runnable.class));
        verify(emitter).onTimeout(any(Runnable.class));
        verify(emitter).onError(any());
        verifyNoMoreInteractions(emitter);
    }

    /**
     * 取消信号要如实反映连接状态：初始未取消；推送失败（客户端断开）后永远为 true。
     *
     * <p>编排层在每个状态执行前查询这个信号来决定要不要继续跑 —— 它必须
     * 在断开后保持 true（离开是不可逆的），否则流程会在下一个状态悄悄复活。
     */
    @Test
    void isCancelledShouldReflectConnectionState() throws Exception {
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        SseStreamListener listener = new SseStreamListener(emitter);

        assertThat(listener.isCancelled()).isFalse();

        // 模拟客户端断开：推送即失败
        willThrow(new IOException("Broken pipe"))
                .given(emitter).send(any(SseEmitter.SseEventBuilder.class));
        listener.onFragment("半个字");

        assertThat(listener.isCancelled()).isTrue();
    }

    /**
     * 多意图轮的后缀补送：第一段流式推送过、第二段是固定话术时，
     * 收尾必须只补"没送达的后缀"，让前端按片段追加后拼出完整正文。
     *
     * <p>补整段会重复第一遍（前端追加渲染下气泡里出现两遍第一段）；
     * 不补则第二段要等 done 替换才现身。按前缀差补后缀是两种都不讨好的中间解。
     */
    @Test
    void completeShouldPushOnlyUndeliveredSuffixForMultiSegmentReply() throws Exception {
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        SseStreamListener listener = new SseStreamListener(emitter);

        listener.start("session-3");
        listener.onFragment("第一段的回答");
        // 权威结果 = 已推送的第一段 + 固定话术的第二段
        listener.complete(ChatReply.builder().content("第一段的回答\n\n请提供您想咨询的商品名").build());

        // 推送次数：session / message(片段) / message(后缀) / done = 4
        verify(emitter, times(4)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
    }

    /**
     * 权威内容与已推送部分不是前缀关系（生成失败后换成兜底话术）时不能补后缀：
     * 拼出来的是乱序文本。这种轮次的完整性由 done 的权威替换兜底。
     */
    @Test
    void completeShouldNotPushSuffixWhenContentIsNotAPrefixExtension() throws Exception {
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        SseStreamListener listener = new SseStreamListener(emitter);

        listener.start("session-4");
        listener.onFragment("推到一半失败的半句话");
        listener.complete(ChatReply.builder().content("抱歉，知识库中没有找到可靠答案，已为您转接人工客服").build());

        // 推送次数：session / message(片段) / done = 3，没有后缀补送
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
    }

    /**
     * 构造时必须注册容器的三个生命周期回调：本轮还没有任何推送时
     * （比如还在意图识别阶段）客户端就断开，只有容器回调能感知到。
     */
    @Test
    void constructorShouldRegisterLifecycleCallbacks() {
        SseEmitter emitter = Mockito.mock(SseEmitter.class);
        new SseStreamListener(emitter);

        verify(emitter).onCompletion(any(Runnable.class));
        verify(emitter).onTimeout(any(Runnable.class));
        verify(emitter).onError(any());
    }
}
