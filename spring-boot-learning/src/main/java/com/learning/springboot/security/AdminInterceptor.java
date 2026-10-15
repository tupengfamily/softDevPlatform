package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.annotation.RequireOperation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 角色权限拦截器(通用版)
 *
 * <p>校验 {@link RequireAdmin} / {@link RequireOperation} 标记:
 * <ul>
 *   <li>{@code @RequireAdmin}: 角色必须含 ADMIN</li>
 *   <li>{@code @RequireOperation}: 角色必须含 ADMIN 或 OPERATION</li>
 * </ul>
 * <p>优先级: 方法级注解 > 类级注解; 多个注解取「最严」(合并检查)。
 * <p>角色来源: JwtAuthFilter 解析 JWT 后写入的 request attribute。
 */
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        // 取方法级或类级注解(方法优先)
        RequireAdmin admin = hm.getMethodAnnotation(RequireAdmin.class);
        if (admin == null) admin = hm.getBeanType().getAnnotation(RequireAdmin.class);
        RequireOperation op = hm.getMethodAnnotation(RequireOperation.class);
        if (op == null) op = hm.getBeanType().getAnnotation(RequireOperation.class);

        // 没有权限注解,放行
        if (admin == null && op == null) return true;

        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(JwtAuthFilter.ATTR_ROLES);

        // @RequireAdmin: 必须 ADMIN
        if (admin != null) {
            if (roles == null || !roles.contains("ADMIN")) {
                deny(req, resp, hm, admin.value(), roles);
                return false;
            }
        }
        // @RequireOperation: ADMIN 或 OPERATION
        if (op != null) {
            if (roles == null || (!roles.contains("ADMIN") && !roles.contains("OPERATION"))) {
                deny(req, resp, hm, op.value(), roles);
                return false;
            }
        }
        return true;
    }

    private void deny(HttpServletRequest req, HttpServletResponse resp, HandlerMethod hm, String message, Set<String> roles) {
        log.warn("[权限] 拒绝访问 {}.{} (角色: {})",
                hm.getBeanType().getSimpleName(), hm.getMethod().getName(), roles);
        try {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 403);
            body.put("message", message);
            body.put("data", null);
            body.put("timestamp", LocalDateTime.now().toString());
            resp.getWriter().write(MAPPER.writeValueAsString(body));
        } catch (Exception ignored) {}
    }
}