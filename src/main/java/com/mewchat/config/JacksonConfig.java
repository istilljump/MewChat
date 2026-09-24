package com.mewchat.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 序列化配置。
 *
 * <p><b>本类解决的是一个会造成前端诡异 bug 的问题</b>：
 * 全库主键用的是雪花算法生成的 {@code Long}，有 19 位（约 1.7×10^18），
 * 而 JavaScript 的 {@code Number} 安全整数上限是 2^53 ≈ 9.0×10^15（16 位）。
 * JSON 里的数字被 JS 解析时<b>会静默丢精度</b>，表现为
 * "列表里点进去 ID 变了""详情接口查不到数据"——而且后端日志一切正常，极难定位。
 *
 * <p>解决办法是把所有 {@code Long} 序列化成字符串。前端统一当字符串处理即可。
 *
 * <p>为什么不逐个字段加 {@code @JsonSerialize}：主键、外键、各种ID散落在几十个
 * 实体和 DTO 里，逐个标注既容易漏、又污染实体。全局配置一次到位。
 *
 * <p>对反序列化的影响：无。Jackson 默认就能把 JSON 里的字符串或数字转成 {@code Long}，
 * 所以前端传字符串ID或数字ID都能正常接收。
 *
 * @author MewChat
 */
@Configuration
public class JacksonConfig {

    /**
     * 把 {@code Long} 与基本类型 {@code long} 都序列化为字符串。
     *
     * <p>两个类型都要注册：Jackson 区分装箱类型与基本类型，
     * 只注册 {@code Long.class} 会漏掉实体里声明为 {@code long} 的字段。
     *
     * @return ObjectMapper 定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
