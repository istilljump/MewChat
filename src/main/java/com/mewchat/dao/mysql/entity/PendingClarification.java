package com.mewchat.dao.mysql.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 挂起的澄清追问 —— JSON 值对象，<b>不是一张表</b>。
 *
 * <p>它是 {@link Conversation#getPendingClarification()} 这一列的内容，
 * 记录"上一轮问了什么、用户在哪些候选里挑"。
 * 用户在下一轮回复一个序号时，靠它才能把"1"还原成"订单 MC202409240001"。
 *
 * <p><b>为什么必须落库、不能放内存</b>：看这一串数字就够了 —— 用户回复"1"
 * 时可能已经过了几分钟、可能落在另一台实例上、可能中间发生过重启。
 * 放内存的话，这几件事都会让"1"变成一个无法理解的新问题
 * （拿"1"去做意图识别，模型只能给出 UNKNOWN）。这与会话记忆不放内存是同一个理由。
 *
 * <p>为什么不建独立表：它与会话是严格的一对一、生命周期完全跟随会话
 * （被回答或过期即清空），独立成表只会多一次 join 和一张需要清理的空表。
 *
 * @author MewChat
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingClarification implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 触发追问的原问题，用于用户选中候选后拼出一句完整的上下文 */
    private String question;

    /**
     * 当时的意图名。
     *
     * <p>存字符串而不是枚举：枚举将来改名或删除常量时，
     * 已经落库的 JSON 会直接反序列化失败（整个查询报错），
     * 而字符串最多是解析不出意图、按新问题重走一遍。
     */
    private String intent;

    /** 缺失的参数名，用户选中候选项后填的就是它 */
    private String missingParam;

    /** 提供给用户的候选项 */
    @Builder.Default
    private List<Option> options = new ArrayList<>();

    /** 挂起时间，用于判断是否已经过期 */
    private LocalDateTime createdAt;

    /**
     * 候选项。
     *
     * <p>分开存 value 与 label：value 是要填进参数的值（订单号），
     * label 是给人看的文案。若只存一个字段，就得在解析时再想办法把它们拆开 ——
     * 而"从展示文案里反解业务值"是最容易出错的一类代码。
     */
    @Getter
    @Setter
    @ToString
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Option implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 参数值（如订单号） */
        private String value;

        /** 展示给用户的文案 */
        private String label;
    }
}
