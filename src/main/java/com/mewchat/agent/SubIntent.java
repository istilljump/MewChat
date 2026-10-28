package com.mewchat.agent;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 多意图拆解出的一个子意图。
 *
 * <p>用户一句话里问了多件不同的事（"帮我查订单 MC... 的状态，再看看耳机多少钱"）时，
 * 意图识别（多意图模式）会把它们拆成若干条 {@link SubIntent}，由编排层的受控环
 * 逐条执行（见 {@code ChatState.AGGREGATE}）。每条子意图携带自己的意图、置信度、
 * 改写查询与参数 —— <b>改写查询只描述本条问题</b>：混入别的问题会让检索
 * 同时命中两摊内容，答案两头都答不好。
 *
 * @param intent         子意图
 * @param confidence     该条意图的置信度，0~1
 * @param rewrittenQuery 该条问题的独立改写（适合检索/工具参数抽取）
 * @param params         该条问题抽出的参数
 * @author MewChat
 */
public record SubIntent(IntentType intent,
                        BigDecimal confidence,
                        String rewrittenQuery,
                        Map<String, Object> params) {
}
