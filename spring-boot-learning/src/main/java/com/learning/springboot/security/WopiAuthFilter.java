package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.config.OnlyOfficeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WOPI 鉴权 Filter.
 * <p>
 * 规则:
 * <ul>
 *   <li>只拦 /wopi/** 路径</li>
 *   <li>从 query 取 access_token(OnlyOffice 习惯)</li>
 *   <li>用 OnlyOfficeJwtUtil 验证 token</li>
 *   <li>成功: 把 userId / username 写到 request attribute,放行</li>
 *   <li>失败: 直接 401 JSON</li>
 * </ul>
 * JWT body 约定: { "userId": 1, "username": "admin", ... }
 */
@Component
public class WopiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WopiAuthFilter.class);

    public static final String ATTR_USER_ID = "wopiUserId";
    public static final String ATTR_USERNAME = "wopiUsername";

    @Autowired private OnlyOfficeJwtUtil jwt;
    @Autowired private OnlyOfficeProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/wopi");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // 没启用 OO 时直接放行(WOPI 端点会被 controller 兜底返 503)
        if (!props.isEnabled()) {
            chain.doFilter(req, resp);
            return;
        }
        if (props.isJwtEnabled()) {
            String token = req.getParameter("access_token");
            if (token == null || token.isEmpty()) {
                token = req.getHeader(props.getJwtHeader());
                if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
            }
            if (token == null || token.isEmpty()) {
                writeError(resp, 401, "missing access_token");
                return;
            }
            try {
                Map<String, Object> payload = jwt.parseRequired(token);
                Object uid = payload.get("userId");
                if (uid == null) {
                    writeError(resp, 401, "missing userId in token");
                    return;
                }
                Long userId = (uid instanceof Number n) ? n.longValue() : Long.parseLong(uid.toString());
                req.setAttribute(ATTR_USER_ID, userId);
                req.setAttribute(ATTR_USERNAME, payload.getOrDefault("username", "anon"));
            } catch (Exception ex) {
                log.warn("WOPI token invalid: {}", ex.getMessage());
                writeError(resp, 401, "invalid token: " + ex.getMessage());
                return;
            }
        }
        chain.doFilter(req, resp);
    }

    private void writeError(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", msg);
        resp.getWriter().write(mapper.writeValueAsString(body));
    }

    /** 注册只拦 /wopi/** 的 filter */
    @Bean
    public FilterRegistrationBean<WopiAuthFilter> wopiAuthFilterRegistration(WopiAuthFilter f) {
        FilterRegistrationBean<WopiAuthFilter> reg = new FilterRegistrationBean<>(f);
        reg.addUrlPatterns("/wopi/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);  // 早于其它 filter
        return reg;
    }
}
