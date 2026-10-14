package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.ChangePasswordRequest;
import com.learning.springboot.dto.LoginRequest;
import com.learning.springboot.dto.LoginResponse;
import com.learning.springboot.dto.ProfileUpdateRequest;
import com.learning.springboot.dto.RegisterRequest;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.security.LoginRateLimiter;
import com.learning.springboot.service.AuthService;
import com.learning.springboot.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 认证控制器: 注册、登录、获取当前用户、登出
 * <p>
 * 路径前缀 /api/auth,免鉴权(JwtAuthFilter 已排除)
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private LoginRateLimiter loginRateLimiter;

    /** 是否信任反向代理的 X-Forwarded-For / X-Real-IP(默认关闭, 防 IP 伪造) */
    @Value("${app.trust-proxy:false}")
    private boolean trustProxy;

    @PostMapping("/login")
    @OperationLog(module = "认证", action = "登录", recordParams = false)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        LoginResponse data = authService.login(req, ip);
        return Result.ok("登录成功", data);
    }

    @PostMapping("/register")
    @OperationLog(module = "认证", action = "注册", recordParams = false)
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest httpReq) {
        String ip = clientIp(httpReq);
        // 注册限流: 每 IP 窗口期内有次数上限, 超限 429
        loginRateLimiter.checkRegisterAllowed(ip);
        LoginResponse data = authService.register(req, ip);
        return Result.created("注册成功", data);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@CurrentUser Long userId) {
        if (userId == null) throw new BusinessException(401, "未登录");
        return Result.ok(authService.me(userId));
    }

    /** 本人更新个人信息(昵称/头像/个性签名/邮箱/年龄/地址) */
    @PutMapping("/profile")
    @OperationLog(module = "认证", action = "更新个人信息", recordParams = false)
    public ResponseEntity<?> updateProfile(@CurrentUser Long userId,
                                           @Valid @RequestBody ProfileUpdateRequest req) {
        if (userId == null) throw new BusinessException(401, "未登录");
        return Result.ok("个人信息已更新", authService.updateProfile(userId, req));
    }

    /**
     * 用户自行修改密码(已登录态,必须验证旧密码; 仅作用于当前用户本人,
     * 区别于管理员重置他人密码的 PUT /api/users/{id}/password)。
     * <p>
     * 旧密码正确后, BCrypt 重置; 写操作日志(已对密码字段脱敏)。
     */
    @PutMapping("/password")
    @OperationLog(module = "认证", action = "修改自己的密码", recordParams = false)
    public ResponseEntity<?> changeMyPassword(@CurrentUser Long userId,
                                              @Valid @RequestBody ChangePasswordRequest req) {
        if (userId == null) throw new BusinessException(401, "未登录");
        if (req.getNewPassword().equals(req.getOldPassword())) {
            throw new BusinessException(400, "新密码不能与旧密码相同");
        }
        userService.changePassword(userId, req);
        return Result.ok("密码已修改, 请重新登录", null);
    }

    /** 上传头像图片(multipart/form-data, 字段名 file),返回新头像 URL */
    @PostMapping("/avatar")
    @OperationLog(module = "认证", action = "上传头像", recordParams = false)
    public ResponseEntity<?> uploadAvatar(@CurrentUser Long userId,
                                          @RequestParam("file") MultipartFile file) {
        if (userId == null) throw new BusinessException(401, "未登录");
        String url = authService.updateAvatar(userId, file);
        return Result.ok("头像已更新", url);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        // 无状态: 服务端不持有 session,前端清除 token 即可
        return Result.ok("已登出", null);
    }

    /**
     * 取客户端 IP: 默认直接用 TCP 对端地址(不信任可伪造的请求头);
     * 仅当 app.trust-proxy=true(确认部署在可信反代之后)才解析 X-Forwarded-For / X-Real-IP。
     */
    private String clientIp(HttpServletRequest req) {
        if (trustProxy) {
            String ip = req.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = req.getHeader("X-Real-IP");
            }
            if (ip != null && !ip.isEmpty()) {
                return ip.split(",")[0].trim();
            }
        }
        String ip = req.getRemoteAddr();
        return ip == null ? "" : ip;
    }
}