package com.mewchat.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mewchat.service.MessageService;

/**
 * 反馈取值：有用 / 无用。
 *
 * <p><b>对外用字符串（{@code up} / {@code down}），对内映射到
 * {@link MessageService#FEEDBACK_UP} 等常量。</b>这么绕一层是为了让两边都少犯错：
 * 前端不必知道"1 是有用还是 2 是有用"这种靠记忆的约定；服务端的取值来源仍只有一处
 * （常量定义在 service 上），不会出现"接口层写死 1、服务层改了常量"的静默错位。
 *
 * @author MewChat
 */
public enum FeedbackVote {

    /** 有用 */
    UP("up", MessageService.FEEDBACK_UP),

    /** 无用 */
    DOWN("down", MessageService.FEEDBACK_DOWN);

    private final String value;

    private final int stored;

    FeedbackVote(String value, int stored) {
        this.value = value;
        this.stored = stored;
    }

    /**
     * 序列化成对外的字符串形式。
     *
     * @return {@code up} 或 {@code down}
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 落库时用的数值。
     *
     * @return 1（有用）或 2（无用）
     */
    public int stored() {
        return stored;
    }

    /**
     * 由请求体里的字符串解析。
     *
     * <p>非法值直接抛 {@link IllegalArgumentException}，由 Spring 包成
     * {@code HttpMessageNotReadableException}、再被全局处理器转成 10001 ——
     * 不需要在这里返回 null 之类的特殊值。
     *
     * @param raw 请求体里的取值
     * @return 枚举值
     * @throws IllegalArgumentException 取值不是 up / down 时抛出
     */
    @JsonCreator
    public static FeedbackVote fromJson(String raw) {
        if (raw != null) {
            for (FeedbackVote vote : values()) {
                if (vote.value.equalsIgnoreCase(raw.trim())) {
                    return vote;
                }
            }
        }
        throw new IllegalArgumentException("反馈值只能是 up 或 down");
    }

    /**
     * 把库里存的数值还原成对外取值。
     *
     * @param stored 数据库里的取值，可为 null
     * @return 对应的枚举；未反馈（null）或取值未知时返回 null
     */
    public static FeedbackVote of(Integer stored) {
        if (stored == null) {
            return null;
        }
        for (FeedbackVote vote : values()) {
            if (vote.stored == stored) {
                return vote;
            }
        }
        // 未知取值当作"未反馈"而不是抛异常：历史数据或将来新增的取值
        // 不该让用户的历史记录整个读不出来
        return null;
    }
}
