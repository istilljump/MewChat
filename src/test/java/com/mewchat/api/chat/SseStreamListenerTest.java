package com.mewchat.api.chat;

import com.mewchat.agent.ChatReply;
import com.mewchat.agent.ChatState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

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
        verifyNoMoreInteractions(emitter);
    }
}
