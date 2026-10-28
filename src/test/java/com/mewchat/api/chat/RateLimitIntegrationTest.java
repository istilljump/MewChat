package com.mewchat.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.common.exception.BizException;
import com.mewchat.common.result.ResultCode;
import com.mewchat.common.security.TokenCodec;
import com.mewchat.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对话限速的接口级测试：限流参数刻意调小（每分钟 1 轮），
 * 让"超限"能在一两条请求内触发。
 *
 * <p>限流状态是进程内存、跨用例共享的，因此用独立的用户ID 发请求，不与其它用例串味。
 * 第一条请求故意让"会话归属校验"失败来消耗配额 —— 归属校验在限流<b>之后</b>执行，
 * 这样既扣掉了名额，又完全不需要真的建立 SSE 连接（超限断言因此是纯同步的 JSON 对比）。
 *
 * @author MewChat
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mewchat_rate;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "mewchat.auth.token-secret=rate-test-secret-0123456789abcdef",
        "mewchat.rate-limit.chat-requests-per-minute=1"
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    private static final String SESSION_ID = "7c1f0b9a3e5d4f2a8b6c1d0e9f8a7b6c";

    /** 专用用户：限流状态是进程内存、跨用例共享，独立用户ID 才不与其它用例串味 */
    private static final long CHAT_USER_ID = 910000000000000001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenCodec tokenCodec;

    @MockitoBean
    private ConversationService conversationService;

    /**
     * 对话限速：第一条请求消耗配额（以归属校验失败收场），同窗口内第二条被拒（20003）。
     *
     * <p>拒绝发生在建 SSE 连接之前 —— 超限的调用方拿到的是一个 JSON 错误，
     * 而不是一串状态事件之后才报错。
     */
    @Test
    void chatShouldBeRateLimitedPerUserPerMinute() throws Exception {
        String token = tokenCodec.issue(CHAT_USER_ID, "chatter", 1).token();
        willThrow(new BizException(ResultCode.FORBIDDEN, "会话不存在或无权访问"))
                .given(conversationService).resolveOwnedSession(anyString(), eq(CHAT_USER_ID));

        // 第一条：过了限流、死在归属校验（20002）—— 配额已被消耗
        mockMvc.perform(send("你好", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.FORBIDDEN.getCode()));

        // 第二条：同窗口内配额已满，还没走到归属校验就被拒（20003）
        mockMvc.perform(send("再来一句", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));
    }

    private MockHttpServletRequestBuilder send(String message, String token) throws Exception {
        String body = new ObjectMapper().writeValueAsString(
                Map.of("sessionId", SESSION_ID, "message", message));
        return post("/api/chat/send")
                .header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
