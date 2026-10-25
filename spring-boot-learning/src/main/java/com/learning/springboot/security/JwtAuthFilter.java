package com.learning.springboot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JWT 鉴权过滤器
 * <p>
 * - 从 Authorization 头解析 Bearer Token
 * - 解析成功: 把 userId/username/roles 写入 request attribute,放行
 * - 解析失败: 直接返回 401 JSON 响应
 * <p>
 * 不需要登录的接口: /api/auth/**、/api/hello/**
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    public static final String ATTR_USER_ID = "currentUserId";
    public static final String ATTR_USERNAME = "currentUsername";
    public static final String ATTR_ROLES = "currentRoles";

    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserRepository userRepository;

    /** 游客可运行沙箱开关(默认关闭, 沙箱执行接口一律需要登录) */
    @org.springframework.beans.factory.annotation.Value("${app.sandbox.allow-guest-run:false}")
    private boolean allowGuestRun;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 非 /api/ 路径直接放行
        if (!path.startsWith("/api/")) return true;
        // 公开接口白名单(必须能匿名访问)
        // 注意: /api/auth/login 和 /api/auth/register 必须在此白名单,
        //       但 /api/auth/me 和 /api/auth/logout 需要鉴权, 走 filter
        return path.equals("/api/auth/login")
            || path.equals("/api/auth/register")
            || path.equals("/api/auth/public-key")    // RSA 公钥(登录前需要,免鉴权)
            || path.startsWith("/api/track/")        // 前端埋点上报(免鉴权, 内部 IP 限流)
            || path.equals("/api/hello")
            || path.startsWith("/api/hello/")
            || (allowGuestRun && path.startsWith("/api/sandbox/run-"))   // 游客试练开关(默认关闭); reset-sql 仍需登录
            || path.startsWith("/api/public/wiki/")   // Wiki 分享页: 凭令牌公开只读
            || path.equals("/api/public/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String token = jwtUtil.resolveToken(req);
        if (token == null || token.isEmpty()) {
            // 游客可读接口:不带 token 也放行(不写入用户属性,userId 为空)
            // 支撑"游客访问学习中心":浏览课程/课时/补充内容,个人操作仍需登录
            if (isGuestReadable(req)) {
                chain.doFilter(req, resp);
                return;
            }
            writeUnauthorized(resp, "缺少认证凭证");
            return;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = jwtUtil.extractUserId(claims);
            String username = claims.getSubject();
            Set<String> roles = jwtUtil.extractRoles(claims);

            // SSO: 校验 tokenVersion (单点登出/退出所有设备)
            // 用户登出时 User.tokenVersion+1, 旧 token 立即失效(单设备登出影响所有 Tab/设备)
            if (userId != null) {
                Long tokenTv = jwtUtil.extractTokenVersion(claims);
                Long userTv = userRepository.findTokenVersionById(userId);
                if (userTv == null) {
                    // 🔴 2026-10-25：用户已不存在（被删除）。旧 token 必须立即失效，
                    // 原来这里因 userTv == null 直接跳过比对 → 被删账号的 token 仍可用。
                    log.info("[JWT] userId={} 不存在 → token 已失效", userId);
                    writeUnauthorized(resp, "账号不存在或已被删除");
                    return;
                }
                if (tokenTv != null && tokenTv < userTv) {
                    log.info("[JWT] token tv={} < user tv={} for userId={} → token 已失效",
                            tokenTv, userTv, userId);
                    writeUnauthorized(resp, "Token 已失效(账号在其他位置登出或密码已修改)");
                    return;
                }
            }

            req.setAttribute(ATTR_USER_ID, userId);
            req.setAttribute(ATTR_USERNAME, username);
            req.setAttribute(ATTR_ROLES, roles);

            chain.doFilter(req, resp);
        } catch (JwtException e) {
            log.warn("[JWT] 解析失败: {}", e.getMessage());
            writeUnauthorized(resp, "Token 无效或已过期");
        } catch (Exception e) {
            log.error("[JWT] 鉴权异常", e);
            writeUnauthorized(resp, "认证失败");
        }
    }

    /**
     * 游客可读接口(匿名 GET 放行,userId 为空):
     * 科目列表 / 课程树 / 课时详情 / 补充内容树 — 支撑游客浏览学习中心。
     * 个人相关接口(进度、标记完成、偏好、看板、写操作)仍要求登录。
     *
     * <p>天地图代理({@code /api/map/tianditu/**})也在此列:
     * 瓦片由浏览器 {@code <img>} 直接加载, 根本不会带 Authorization 头,
     * 若走鉴权流程会直接 401 → 地图空白。放在这里而非 shouldNotFilter 白名单,
     * 是为了让「带 token 的请求仍能解析出 userId」—— 将来若要在地图上做
     * 「我的农场在地图上高亮」这类需要身份的功能, 不用改架构。
     */
    private boolean isGuestReadable(HttpServletRequest req) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) return false;
        String path = req.getRequestURI();
        return path.equals("/api/learning/subjects")
            || path.equals("/api/learning/curriculum")
            || path.matches("/api/learning/lessons/\\d+")
            || path.equals("/api/learning/supplements")
            || path.startsWith("/api/map/tianditu/")  // 天地图代理(底图/瓦片/地名搜索, 公开地理数据)
            || path.equals("/api/dicts/map");   // 数据字典标签映射(只读),业务页展示文案用
    }

    private void writeUnauthorized(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(HttpStatus.UNAUTHORIZED.value());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 401);
        body.put("message", message);
        body.put("data", null);
        body.put("timestamp", java.time.LocalDateTime.now().toString());
        resp.getWriter().write(new ObjectMapper().writeValueAsString(body));
    }
}