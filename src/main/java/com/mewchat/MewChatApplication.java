package com.mewchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MewChat 电商 AI 智能客服系统启动类。
 *
 * <p>开启的能力：
 * <ul>
 *     <li>{@link ConfigurationPropertiesScan} —— 扫描 {@code com.mewchat.config} 下
 *         的 {@code @ConfigurationProperties} 配置类</li>
 *     <li>{@link EnableAsync} —— 支持异步任务</li>
 *     <li>{@link EnableScheduling} —— 支持 {@code com.mewchat.job} 下的定时任务</li>
 * </ul>
 *
 * <p>被排除的自动配置：
 * <ul>
 *     <li>{@link UserDetailsServiceAutoConfiguration} —— 不排除时 Spring Security
 *         会自动注册一个内存用户并打印随机密码，造成"已经启用认证"的错觉。
 *         本项目由 {@code config.SecurityConfig} 显式定义过滤链，
 *         认证模块落地后再提供自己的 {@code UserDetailsService}</li>
 *     <li>{@code dev.langchain4j.openai.spring.AutoConfig} —— LangChain4j 的 OpenAI
 *         自动配置。本项目由 {@code config.AiModelConfig} 独占 ChatModel、
 *         StreamingChatModel、EmbeddingModel 三个 Bean 的创建，
 *         若不排除会出现同一接口多个 Bean、注入歧义的冲突</li>
 * </ul>
 *
 * <p>Mapper 扫描已移至 {@code config.MyBatisPlusConfig} 的 {@code @MapperScan}，
 * 与 MyBatis-Plus 的其他配置放在一起。
 *
 * @author MewChat
 */
@SpringBootApplication(exclude = {
        UserDetailsServiceAutoConfiguration.class,
        dev.langchain4j.openai.spring.AutoConfig.class
})
@ConfigurationPropertiesScan("com.mewchat.config")
@EnableAsync
@EnableScheduling
public class MewChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(MewChatApplication.class, args);
    }
}
