package com.learning.springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器(所有响应统一附加)
 * <ul>
 *   <li>X-Content-Type-Options: nosniff — 禁止 MIME 嗅探(防上传文件被当 HTML 执行)</li>
 *   <li>X-Frame-Options: SAMEORIGIN — 防点击劫持</li>
 *   <li>Referrer-Policy: strict-origin-when-cross-origin — 控制 Referer 泄露</li>
 *   <li>X-XSS-Protection: 0 — 关闭浏览器遗留 XSS 过滤器(其行为不可靠,交由 CSP/消毒)</li>
 * </ul>
 * 由 {@link AdminSecurityConfig#securityHeadersRegistration()} 注册(避免 @Component 双重注册)。
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        resp.setHeader("X-XSS-Protection", "0");
        chain.doFilter(req, resp);
    }
}
