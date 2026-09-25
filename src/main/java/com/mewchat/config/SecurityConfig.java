package com.mewchat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewchat.common.result.Result;
import com.mewchat.common.result.ResultCode;
import com.mewchat.common.security.AuthenticatedUser;
import com.mewchat.common.security.TokenCodec;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置：无状态令牌鉴权。
 *
 * <p><b>与之前版本的变化</b>：早先为方便联调而 {@code anyRequest().permitAll()} 全量放行，
 * 现在收窄为"除登录与健康检查外，一律要求认证"。这一步是必须的 ——
 * 对话接口承载的是用户的会话历史与订单信息，全量放行等于任何人都能读到别人的对话。
 *
 * <p><b>鉴权方式</b>：请求头 {@code Authorization: Bearer <token>}。
 * 令牌由 {@link TokenCodec} 签发与校验，服务端不存会话
 * （{@code SessionCreationPolicy.STATELESS}），因此可以随意多实例部署。
 * 之所以自建过滤器而不用 Spring Security 的表单/会话体系：本服务是前后端分离的
 * REST + SSE 接口，没有登录页也没有 Cookie 会话，用会话机制反而要额外关掉一堆默认行为。
 *
 * <p><b>未认证与无权限都返回统一 JSON</b>：默认行为是返回空 body 的 401/403，
 * 前端拿不到可解析的结构、只能靠状态码猜。这里改为返回
 * {@link Result}（{@code code=20001/20002}），与其它接口一致。
 *
 * <p><b>SSE 与鉴权的关系</b>：浏览器原生的 {@code EventSource} 无法自定义请求头，
 * 因此带令牌的 SSE 需要用 {@code fetch} + {@code ReadableStream} 自行解析事件流
 * （或把令牌放查询参数，但那会让令牌出现在访问日志里，不推荐）。
 * 接口本身对两种客户端都没有区别。
 *
 * @author MewChat
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Bearer 令牌前缀，不区分大小写 */
    private static final String BEARER_PREFIX = "bearer ";

    /**
     * 免认证路径：登录接口、静态页面与健康检查占位。
     *
     * <p><b>静态页面（对话前端）必须放行</b>：浏览器第一次打开
     * {@code http://host:8080/} 时还没有令牌，若页面本身也要认证，
     * 用户会看到一个 401 JSON 而不是登录页 —— 那就没法登录了。
     * 放行的是"不含任何数据的骨架"（HTML/CSS/JS），页面里的每个数据请求
     * （会话列表、历史、发消息）仍然要带令牌、仍然逐个校验归属。
     *
     * <p>这里刻意不列 {@code /actuator/health}：项目没有引入 actuator 依赖，
     * 那条路径实际返回 404，留着会让人以为存在健康检查端点
     * （部署方按它做存活探测会一直失败）。真要健康端点时应先补依赖再放行。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/favicon.ico",
            "/",
            "/index.html",
            "/assets/**"
    };

    private final ObjectMapper objectMapper;

    /**
     * 允许跨域访问的来源，逗号分隔。
     *
     * <p>留空表示不放行任何跨域请求（仅同源）。前后端分离部署时必须显式配置，
     * <b>不要用通配符</b>：接口带的是 Bearer 令牌，通配来源等于允许任意网站
     * 拿着用户的令牌调用本服务。
     */
    private final String allowedOrigins;

    public SecurityConfig(ObjectMapper objectMapper,
                          @Value("${mewchat.cors.allowed-origins:}") String allowedOrigins) {
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 安全过滤器链。
     *
     * @param http       Spring Security 构建器
     * @param tokenCodec 令牌编解码器
     * @return 安全过滤器链
     * @throws Exception 构建过程中的异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenCodec tokenCodec) throws Exception {
        http
                // 纯 REST/SSE 接口，无表单提交；令牌放在请求头而非 Cookie，
                // 因此不存在 CSRF 的攻击面（浏览器不会自动带上自定义请求头）
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // ===== 运营后台按"工作面"分权（hasRole 会自动补 ROLE_ 前缀）=====
                        //
                        // 一线工作面：工单处理与对话记录。客服接单后必须能看到用户的完整对话，
                        // 否则拿着"用户问题：赠品什么时候发货；置信度 0.00"这一行工单描述，
                        // 根本无法还原上下文 —— 放不开对话记录的工作台是残的。
                        // 这两个分组的读接口能看任意用户的对话，属于高权限面，
                        // 因此仍然只认角色、不落任何客户令牌
                        .requestMatchers("/api/admin/tickets/**", "/api/admin/conversations/**")
                                .hasAnyRole("AGENT", "ADMIN")
                        // 治理面：知识库与数据统计。改知识库会影响此后<b>所有</b>回答，
                        // 统计与飞轮收口是运营决策 —— 一线不该有这个权限面
                        // （客服能改知识库的世界里，一次误操作的代价是全量回答质量）。
                        // 这两个分组也涵盖后台的全部写操作，写操作只放给管理员
                        .requestMatchers("/api/admin/knowledge/**", "/api/admin/analytics/**")
                                .hasRole("ADMIN")
                        // 后台兜底规则：以后新增的分组默认只对管理员开放，
                        // 要放开给客服必须像上面那样显式声明 —— 宁可"新接口客服调不通"
                        // 被及时发现，也不要"新接口悄悄对一线敞开"
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 其余一律要求认证：新增接口默认是受保护的，
                        // 忘记配置的表现是"新接口调不通"，而不是"新接口裸奔"
                        .anyRequest().authenticated()
                )
                // 令牌过滤器要排在授权判断之前，否则解析出的身份来不及参与本次鉴权
                .addFilterBefore(tokenAuthenticationFilter(tokenCodec),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(request, response, HttpStatus.UNAUTHORIZED,
                                        ResultCode.UNAUTHORIZED, "未登录或登录已过期，请重新登录"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(request, response, HttpStatus.FORBIDDEN,
                                        ResultCode.FORBIDDEN, "无访问权限")))
                .headers(headers -> headers.frameOptions(Customizer.withDefaults()));

        List<String> origins = parseOrigins();
        if (origins.isEmpty()) {
            log.info("未配置 mewchat.cors.allowed-origins，仅允许同源访问");
        } else {
            http.cors(cors -> cors.configurationSource(corsSource(origins)));
            log.info("已放行跨域来源：{}", origins);
        }

        return http.build();
    }

    /**
     * 密码编码器。
     *
     * <p>用 BCrypt 而不是 MD5/SHA：后者是快速哈希，攻击者拿到库后可以每秒试上亿次；
     * BCrypt 自带盐且计算代价可调，"撞库"的成本被抬到不可行。
     * 用户表里 {@code password} 字段存的就是本编码器产出的密文。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /* ==================== 令牌过滤器 ==================== */

    /**
     * 从请求头解析令牌并建立认证上下文。
     *
     * <p><b>校验失败不直接报错，而是继续放行</b>：这个过滤器只负责"能证明身份时把身份放进去"，
     * 拒绝与否由后续的授权规则决定。这样免认证路径（如登录接口）即使带了
     * 一个过期令牌也能正常访问，而不是被过滤器挡在门外。
     *
     * <p>写成 lambda 而不是独立的 {@code @Component} 过滤器类，有两个原因：
     * 一是 Spring Boot 会把容器里所有 {@code Filter} Bean 自动注册到 Servlet 容器，
     * 那样它会独立于安全链再跑一遍（重复执行）；二是它只服务于本配置，
     * 单独成类反而让人以为它可以在别处复用。
     *
     * @param tokenCodec 令牌编解码器
     * @return 过滤器
     */
    private Filter tokenAuthenticationFilter(TokenCodec tokenCodec) {
        return (request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String token = resolveBearerToken(httpRequest.getHeader(HttpHeaders.AUTHORIZATION));

            if (token != null) {
                tokenCodec.verify(token).ifPresent(payload -> {
                    AuthenticatedUser principal = new AuthenticatedUser(
                            payload.userId(), payload.username(), payload.userType());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    principal, null,
                                    List.of(new SimpleGrantedAuthority(principal.authority())));
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(httpRequest));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }

            chain.doFilter(request, response);
        };
    }

    /**
     * 从 Authorization 头中取出 Bearer 令牌。
     *
     * @param header Authorization 头的值，可为 null
     * @return 令牌；不是 Bearer 形式时返回 null
     */
    private String resolveBearerToken(String header) {
        if (!StringUtils.hasText(header) || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        // 忽略大小写：各客户端对 "Bearer" 的大小写写法并不统一
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    /* ==================== 响应与跨域 ==================== */

    /**
     * 以统一结构写出鉴权失败响应。
     *
     * @param request  请求
     * @param response 响应
     * @param status   HTTP 状态码
     * @param code     业务错误码
     * @param message  提示信息
     * @throws IOException 写出失败时抛出
     */
    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, ResultCode code, String message) throws IOException {
        log.warn("鉴权失败：{} {} 状态={}", request.getMethod(), request.getRequestURI(), status.value());

        // 状态码与字符集必须在取 writer 之前设置，否则响应头已经提交、改动不生效
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }

    /**
     * 构造跨域配置。
     *
     * @param origins 允许的来源
     * @return 跨域配置源
     */
    private CorsConfigurationSource corsSource(List<String> origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        // 方法清单必须覆盖接口实际用到的全部动词：知识库删除用的是 DELETE
        // （KnowledgeAdminController#deleteDocument）。漏掉它时预检响应里没有 DELETE，
        // 浏览器会直接拦下该请求 —— 其余接口都正常，只有这个功能在前端"点了没反应"，
        // 而服务端日志里连请求都看不到，排查方向很容易被带偏
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        // 不开放凭证：身份靠 Authorization 头传递，不依赖 Cookie。
        // 开着它只会让"来源配置写错"的后果更严重
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        return request -> configuration;
    }

    /**
     * 解析允许的跨域来源。
     *
     * @return 来源列表，未配置时为空列表
     */
    private List<String> parseOrigins() {
        if (!StringUtils.hasText(allowedOrigins)) {
            return List.of();
        }
        List<String> origins = new ArrayList<>();
        for (String origin : Arrays.asList(allowedOrigins.split(","))) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        return origins;
    }
}
