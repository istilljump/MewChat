package com.mewchat.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mewchat.common.constant.CommonConstants;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * @author MewChat
 */
@Configuration
@MapperScan("com.mewchat.dao")
public class MyBatisPlusConfig {

    /**
     * JSON 列（{@code typeHandler = JacksonTypeHandler}）专用的序列化器。
     *
     * <p><b>为什么必须显式注册 JavaTimeModule</b>：{@code JacksonTypeHandler} 默认自己
     * new 一个 {@code ObjectMapper}，那个实例<b>没有注册任何模块</b>，
     * 于是含 {@code LocalDateTime} 字段的值对象（如
     * {@link com.mewchat.dao.mysql.entity.PendingClarification} 的 {@code createdAt}）
     * 一旦写库就抛 {@code InvalidDefinitionException: Java 8 date/time type ... not supported}。
     * 后果不只是这一列写不进去：挂起状态存不下来，"追问列候选 → 用户回序号续接"
     * 整条链路在运行时是断的，而编译期、单元测试（Mock 的 Mapper 不做 JSON 序列化）
     * 都毫无察觉。
     *
     * <p><b>为什么不直接复用 Spring 的 ObjectMapper</b>：那个实例被
     * {@code JacksonConfig} 定制成了"所有 Long 序列化成字符串"（为了让前端不丢雪花ID精度）。
     * 复用它会让<b>接口层的一个序列化偏好渗进数据库</b> ——
     * JSON 列里原本是数字的 ID 变成字符串，日后有人调整接口序列化配置，
     * 存量数据与新数据的格式就悄悄分叉了。持久化有持久化自己的格式：
     * java.time 用 ISO-8601 字符串，其余保持 Jackson 默认。
     */
    private static final ObjectMapper JSON_COLUMN_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            // 不用"时间戳数组"形式（[2026,9,24,...]）：JSON 列是要人读的，
            // 而且数组形式在不同 Jackson 小版本间不够稳定
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /**
     * 把专用序列化器交给 {@code JacksonTypeHandler}。
     *
     * <p>它是个静态字段，因此这是全局生效的一次性设置 —— 在容器启动阶段完成，
     * 早于任何一次数据库读写。
     */
    @PostConstruct
    void configureJsonColumnMapper() {
        JacksonTypeHandler.setObjectMapper(JSON_COLUMN_MAPPER);
    }

    /**
     * MyBatis-Plus 插件链。
     *
     * <p>目前只启用了分页插件，其中的单页上限是关键：
     * 不设上限时前端传 {@code size=100000} 就能把整张表捞出来，
     * 一次误操作即可拖垮数据库。
     *
     * <p>如需追加插件，可在此链上继续 addInnerInterceptor，常用的有：
     * <ul>
     *     <li>{@code BlockAttackInnerInterceptor} —— 拦截没有 where 条件的
     *         update/delete，防止全表误操作</li>
     *     <li>{@code OptimisticLockerInnerInterceptor} —— 乐观锁，
     *         需要表里有 {@code @Version} 字段才生效</li>
     * </ul>
     * 注意分页插件必须放在最后一个 addInnerInterceptor（官方要求）。
     *
     * @return 插件链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit((long) CommonConstants.MAX_PAGE_SIZE);
        // 页码超出总页数时返回空列表，而不是回到第一页——
        // 静默回到首页会让调用方误以为"查到了数据"，掩盖分页参数的 bug
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 审计字段自动填充。
     *
     * <p>数据库里 {@code create_time}/{@code update_time} 已有默认值，
     * 但那是"落库后才有值"：Java 对象在 insert 之后仍然是 null，
     * 直接把对象返回给上层就会缺少时间字段。此处在写入前补上。
     *
     * <p>用 {@code strictInsertFill} 而非 {@code setFieldValByName}：
     * 前者只对声明了对应 {@code @TableField(fill = ...)} 的字段生效，
     * 因此 {@code message} 表没有 {@code updateTime} 字段也不会报错。
     *
     * @return 填充处理器
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {

            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
