/**
 * 配置类。
 *
 * <p>职责：把 {@code application.yml} 中的配置绑定成 Bean。
 *
 * <p>约定：
 * <ul>
 *     <li>配置项集中在 yml，类上用 {@code @ConfigurationProperties} 绑定，不散落 {@code @Value}</li>
 *     <li>线程池、HTTP 客户端、Milvus 客户端等基础设施 Bean 在此声明</li>
 * </ul>
 */
package com.mewchat.config;
