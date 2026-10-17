package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.config.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全相关配置
 * <p>
 * - BCryptPasswordEncoder Bean
 * - JWT Filter 注册为 FilterRegistrationBean(只拦截 /api/*)
 * - 注册 CurrentUserResolver
 * - CORS 跨域配置(暴露 Authorization 头)
 */
@Configuration
public class AdminSecurityConfig implements WebMvcConfigurer {

    @Autowired private JwtAuthFilter jwtAuthFilter;
    @Autowired private CurrentUserResolver currentUserResolver;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private UserStatusInterceptor userStatusInterceptor;

    /** CORS 白名单(逗号分隔,来自 app.cors.allowed-origins 配置) */
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /** 密码编码器 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 注册 JWT 鉴权过滤器(只作用于 /api/*) */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration() {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>(jwtAuthFilter);
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("jwtAuthFilter");
        return reg;
    }

    /** 注册安全响应头过滤器(作用于所有请求) */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersRegistration() {
        FilterRegistrationBean<SecurityHeadersFilter> reg = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("securityHeadersFilter");
        return reg;
    }

    /** CORS: 精确白名单(白名单) + 本地通配模式兜底(开发用) */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        // 鉴权走 Authorization 头(非 Cookie),无需 allowCredentials;白名单精确匹配来源
        config.setAllowCredentials(false);
        // 1) 精确白名单(来自 application.yml, 生产环境改为实际域名)
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList());
        // 2) 兜底模式: 本地开发常见端口都允许(Vite 默认 5173, 但沙箱 / 端口冲突时可能被映射到 5174/5175/...)
        //    生产环境如部署到正式域名,白名单已经覆盖,本地模式不影响
        // 3) hsk-cli(Oray 花生壳)公网子域放行:每次新建 tunnel 会换随机子域
        //    `*.aicp.space` 是 hsk-cli 自动分配,`*.kicp.fun` 是 Oray 提供的免费壳域名
        //    都不放行则 CORS 403,页面无法登录;只放 https(避免被 http 中间人劫持)
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://[::1]:*",
            "https://*.aicp.space",
            "https://*.kicp.fun"
        ));
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.addExposedHeader(jwtProperties.getHeader());
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /** 注册当前用户参数解析器 */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }

    /** 注册管理员权限拦截器(@RequireAdmin 标记的接口校验 ADMIN 角色) + 用户状态拦截器(禁用/只读) */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor())
                .addPathPatterns("/api/**");
        registry.addInterceptor(userStatusInterceptor)
                .addPathPatterns("/api/**");
    }

    @Bean
    public AdminInterceptor adminInterceptor() {
        return new AdminInterceptor();
    }
}