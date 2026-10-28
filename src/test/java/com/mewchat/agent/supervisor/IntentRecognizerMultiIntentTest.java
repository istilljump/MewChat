package com.mewchat.agent.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.agent.IntentType;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多意图识别解析的单元测试（不依赖 Spring 与网络）。
 *
 * <p>{@code recognizeAll} 是"受控环"的燃料入口，它的解析容错直接决定
 * 多意图模式的最坏情形：任何输出异常都必须降级成<b>单条 UNKNOWN</b>
 * （等价于单意图模式），而不是抛异常把整轮对话拖进兜底。
 * 另外两条结构性约束在这里钉死：子意图数必须被上限截断（环的圈数封顶）、
 * 近似重复的拆解必须去重（用户不该看到同一段话出现两次）。
 *
 * @author MewChat
 */
class IntentRecognizerMultiIntentTest {

    private static ChatModel modelReturning(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }

    private static IntentRecognizer recognizer(String modelOutput) {
        return new IntentRecognizer(modelReturning(modelOutput), new ObjectMapper());
    }

    /**
     * 规范的 intents 数组：按用户提到的顺序解析，字段一一对应。
     */
    @Test
    void shouldParseIntentsArrayInOrder() {
        List<IntentRecognizer.IntentResult> results = recognizer("""
                {"intents":[
                  {"intent":"ORDER_QUERY","confidence":0.9,
                   "rewrittenQuery":"查询订单 MC202409240001 的状态","params":{"orderNo":"MC202409240001"}},
                  {"intent":"PRODUCT_QUERY","confidence":0.8,
                   "rewrittenQuery":"耳机多少钱","params":{"productName":"耳机"}}
                ]}
                """).recognizeAll("查订单MC202409240001，再看看耳机多少钱", List.of(), 3);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.ORDER_QUERY);
        assertThat(results.get(0).params()).containsEntry("orderNo", "MC202409240001");
        assertThat(results.get(1).intent()).isEqualTo(IntentType.PRODUCT_QUERY);
        assertThat(results.get(1).rewrittenQuery()).isEqualTo("耳机多少钱");
    }

    /**
     * 模型没按多意图输出（回了单对象）：按单条处理，不报错。
     */
    @Test
    void singleObjectShouldYieldSingleResult() {
        List<IntentRecognizer.IntentResult> results = recognizer(
                "{\"intent\":\"ORDER_QUERY\",\"confidence\":0.9,"
                        + "\"rewrittenQuery\":\"查询订单\",\"params\":{}}")
                .recognizeAll("查订单", List.of(), 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.ORDER_QUERY);
    }

    /**
     * 子意图数被上限截断：受控环的圈数必须封顶。
     */
    @Test
    void shouldCapAtMaxIntents() {
        List<IntentRecognizer.IntentResult> results = recognizer("""
                {"intents":[
                  {"intent":"ORDER_QUERY","confidence":0.9,"rewrittenQuery":"问题1","params":{}},
                  {"intent":"PRODUCT_QUERY","confidence":0.9,"rewrittenQuery":"问题2","params":{}},
                  {"intent":"KNOWLEDGE_QA","confidence":0.9,"rewrittenQuery":"问题3","params":{}},
                  {"intent":"COMPLAINT","confidence":0.9,"rewrittenQuery":"问题4","params":{}}
                ]}
                """).recognizeAll("一句话问四件事", List.of(), 3);

        assertThat(results).as("超出上限的子意图必须被截断").hasSize(3);
        assertThat(results.get(2).intent()).isEqualTo(IntentType.KNOWLEDGE_QA);
    }

    /**
     * 近似重复的拆解要去重：模型偶尔会把同一个问题拆成两条。
     */
    @Test
    void duplicateSubIntentsShouldBeDeduplicated() {
        List<IntentRecognizer.IntentResult> results = recognizer("""
                {"intents":[
                  {"intent":"PRODUCT_QUERY","confidence":0.9,"rewrittenQuery":"耳机多少钱","params":{}},
                  {"intent":"PRODUCT_QUERY","confidence":0.9,"rewrittenQuery":"耳机多少钱","params":{}}
                ]}
                """).recognizeAll("耳机多少钱", List.of(), 3);

        assertThat(results).hasSize(1);
    }

    /**
     * 整体输出不可解析：降级为单条 UNKNOWN（等价于单意图模式的最坏情形）。
     */
    @Test
    void unparseableOutputShouldDegradeToSingleUnknown() {
        List<IntentRecognizer.IntentResult> results = recognizer("抱歉，我理解不了这句话。")
                .recognizeAll("一段奇怪的话", List.of(), 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.UNKNOWN);
        assertThat(results.get(0).confidence()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }

    /**
     * 模型抛异常：同样降级为单条 UNKNOWN，不向上抛。
     */
    @Test
    void modelFailureShouldDegradeToSingleUnknown() {
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("模拟模型超时");
            }
        };
        IntentRecognizer intentRecognizer = new IntentRecognizer(broken, new ObjectMapper());

        List<IntentRecognizer.IntentResult> results =
                intentRecognizer.recognizeAll("查订单，再看看耳机", List.of(), 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.UNKNOWN);
    }

    /**
     * intents 数组为空（模型输出空数组）：降级为单条 UNKNOWN，不能返回空列表。
     *
     * <p>返回空列表会让意图识别节点把 UNKNOWN 之外的字段全部留空，
     * 下游的行为无从预期 —— "永不为空"是这个入口的契约。
     */
    @Test
    void emptyIntentsArrayShouldDegradeToSingleUnknown() {
        List<IntentRecognizer.IntentResult> results = recognizer("{\"intents\":[]}")
                .recognizeAll("查订单", List.of(), 3);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.UNKNOWN);
    }
}
