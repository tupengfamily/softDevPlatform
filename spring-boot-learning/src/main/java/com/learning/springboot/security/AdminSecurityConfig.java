package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.config.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** CORS: 让 Vite (5173) 可以跨域访问 8080 */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*");
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

    /** 注册管理员权限拦截器(@RequireAdmin 标记的接口校验 ADMIN 角色) */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor())
                .addPathPatterns("/api/**");
    }

    @Bean
    public AdminInterceptor adminInterceptor() {
        return new AdminInterceptor();
    }
}