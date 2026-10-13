package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.entity.User;
import com.learning.springboot.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户状态拦截器: 按最新用户状态限制请求(状态取自数据库,即时生效,不依赖 JWT 快照)
 * <p>
 * - 禁用(0): 拦截所有请求(仅放行登出),返回 403「账号已被禁用」
 *   (补齐"禁用后旧 token 未过期仍可调接口"的缺口)
 * - 只读(2): 拦截所有写操作(非 GET/HEAD/OPTIONS,仅放行登出),返回 403「只读用户禁止操作」
 *   只读用户只能浏览(查看课程/帖子/Wiki 等默认内容),禁止编辑、发言、收藏、导入导出等操作
 * - 启用(1): 全部放行
 */
@Component
public class UserStatusInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(UserStatusInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true; // 静态资源等直接放行
        }
        Long userId = (Long) req.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (userId == null) {
            return true; // 游客/匿名请求(登录注册等白名单)不在此拦截
        }
        String uri = req.getRequestURI();
        // 登出始终放行,保证禁用/只读用户能正常退出
        if ("/api/auth/logout".equals(uri)) {
            return true;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getStatus() == null) {
            return true; // 用户不存在时交由后续业务返回 404
        }

        if (user.getStatus() == User.STATUS_DISABLED) {
            log.warn("[状态] 禁用账号 {} 访问 {} 被拦截", user.getUsername(), uri);
            writeForbidden(resp, "账号已被禁用,如有疑问请联系管理员");
            return false;
        }

        if (user.getStatus() == User.STATUS_READONLY && !isReadOnlyMethod(req.getMethod())) {
            log.warn("[状态] 只读账号 {} 尝试写操作 {} {} 被拦截", user.getUsername(), req.getMethod(), uri);
            writeForbidden(resp, "当前账号为只读状态,仅可浏览,禁止编辑/发言等操作");
            return false;
        }
        return true;
    }

    /** 只读用户允许的请求方法(纯读取) */
    private boolean isReadOnlyMethod(String method) {
        return "GET".equalsIgnoreCase(method)
            || "HEAD".equalsIgnoreCase(method)
            || "OPTIONS".equalsIgnoreCase(method);
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
