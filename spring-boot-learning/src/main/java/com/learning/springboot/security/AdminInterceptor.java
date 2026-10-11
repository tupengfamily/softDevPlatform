package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.annotation.RequireAdmin;
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
 * 管理员权限拦截器
 * <p>
 * 校验被 {@link RequireAdmin} 标记的控制器方法/类:
 * - 角色集合中包含 ADMIN: 放行
 * - 否则: 返回 403 + 统一 JSON(与 JwtAuthFilter 的 401 响应结构保持一致)
 * <p>
 * 角色来源: JwtAuthFilter 解析 JWT 后写入的 request attribute,无需重复查库。
 */
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // 静态资源等直接放行
        }
        RequireAdmin annotation = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        }
        if (annotation == null) {
            return true; // 未标记,无需管理员权限
        }

        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(JwtAuthFilter.ATTR_ROLES);
        if (roles != null && roles.contains("ADMIN")) {
            return true;
        }

        log.warn("[权限] 拒绝非管理员访问 {}.{} (角色: {})",
                handlerMethod.getBeanType().getSimpleName(), handlerMethod.getMethod().getName(), roles);
        writeForbidden(resp, annotation.value());
        return false;
    }

    private void writeForbidden(HttpServletResponse resp, String message) throws Exception {
        resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("message", message);
        body.put("data", null);
        body.put("timestamp", LocalDateTime.now().toString());
        resp.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
