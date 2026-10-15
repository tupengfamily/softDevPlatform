package com.learning.springboot.service.impl;

import com.learning.springboot.config.JwtProperties;
import com.learning.springboot.dto.LoginRequest;
import com.learning.springboot.dto.LoginResponse;
import com.learning.springboot.dto.ProfileUpdateRequest;
import com.learning.springboot.dto.RegisterRequest;
import com.learning.springboot.entity.Role;
import com.learning.springboot.entity.User;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.PermissionRepository;
import com.learning.springboot.repository.RoleRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.security.JwtUtil;
import com.learning.springboot.security.LoginRateLimiter;
import com.learning.springboot.security.RsaKeyProvider;
import com.learning.springboot.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证服务实现
 */
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private LoginRateLimiter loginRateLimiter;
    @Autowired private RsaKeyProvider rsaKeyProvider;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest req, String clientIp) {
        // 密码解密(encryptedPassword 不为空时优先用 RSA 还原; 否则保留明文兼容老客户端)
        decryptPasswordField(req::getEncryptedPassword, req::setPassword, () -> req.getPassword() == null || req.getPassword().isEmpty(),
            "请提供密码或加密密码");
        // 限流检查: 锁定期内直接 429(防暴力破解)
        loginRateLimiter.checkAllowed(req.getUsername(), clientIp);

        User user;
        try {
            user = userRepository.findByUsernameWithRoles(req.getUsername())
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));

            // 仅禁用(0)拒绝登录;只读(2)可正常登录,登录后由 UserStatusInterceptor 限制写操作
            if (user.getStatus() == null || user.getStatus() == User.STATUS_DISABLED) {
                throw new BusinessException(403, "账号已被禁用");
            }

            if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
                throw new BusinessException(401, "用户名或密码错误");
            }
        } catch (BusinessException e) {
            // 凭证错误计入失败次数(禁用账号不计)
            if (e.getCode() == 401) {
                loginRateLimiter.recordFailure(req.getUsername(), clientIp);
            }
            throw e;
        }
        loginRateLimiter.recordSuccess(req.getUsername(), clientIp);

        // 提取角色编码
        Set<String> roleCodes = new HashSet<>();
        for (Role r : user.getRoles()) roleCodes.add(r.getCode());

        // 颁发 Token
        String token = jwtUtil.generate(user.getId(), user.getUsername(), roleCodes,
            user.getTokenVersion() == null ? 0L : user.getTokenVersion());

        // 更新登录信息
        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        userRepository.save(user);

        // 加载权限
        List<String> perms = permissionRepository.findPermissionCodesByUserId(user.getId());

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setExpiresIn(jwtProperties.getExpirationMillis() / 1000);
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setEmail(user.getEmail());
        resp.setNickname(user.getNickname());
        resp.setAvatar(user.getAvatar());
        resp.setSignature(user.getSignature());
        resp.setAge(user.getAge());
        resp.setAddress(user.getAddress());
        resp.setStatus(user.getStatus());
        resp.setRoles(roleCodes);
        resp.setPermissions(new HashSet<>(perms));
        return resp;
    }

    @Override
    @Transactional
    public LoginResponse register(RegisterRequest req, String clientIp) {
        // 密码解密(encryptedPassword 不为空时优先用 RSA 还原; 否则保留明文兼容老客户端)
        decryptPasswordField(req::getEncryptedPassword, req::setPassword, () -> req.getPassword() == null || req.getPassword().isEmpty(),
            "请提供密码或加密密码");

        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException(409, "用户名已存在: " + req.getUsername());
        }
        if (req.getEmail() != null && userRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new BusinessException(409, "邮箱已被注册");
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail());
        user.setAge(req.getAge() != null ? req.getAge() : 18);
        user.setAddress(req.getAddress());
        user.setStatus(1);

        // 默认赋予 USER 角色
        Role defaultRole = roleRepository.findByCode("USER")
            .orElseThrow(() -> new BusinessException(500, "默认角色 USER 不存在,请先初始化"));
        user.setRoles(new HashSet<>(Set.of(defaultRole)));

        User saved = userRepository.save(user);

        // 自动登录: 颁发 token
        Set<String> roleCodes = new HashSet<>(Set.of("USER"));
        String token = jwtUtil.generate(saved.getId(), saved.getUsername(), roleCodes, 0L);

        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setExpiresIn(jwtProperties.getExpirationMillis() / 1000);
        resp.setUserId(saved.getId());
        resp.setUsername(saved.getUsername());
        resp.setEmail(saved.getEmail());
        resp.setNickname(saved.getNickname());
        resp.setAvatar(saved.getAvatar());
        resp.setSignature(saved.getSignature());
        resp.setAge(saved.getAge());
        resp.setAddress(saved.getAddress());
        resp.setStatus(saved.getStatus());
        resp.setRoles(roleCodes);
        resp.setPermissions(new HashSet<>());
        return resp;
    }

    @Override
    public LoginResponse me(Long userId) {
        User user = userRepository.findByUsernameWithRoles(getUsername(userId))
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        Set<String> roleCodes = new HashSet<>();
        for (Role r : user.getRoles()) roleCodes.add(r.getCode());

        List<String> perms = permissionRepository.findPermissionCodesByUserId(user.getId());

        LoginResponse resp = new LoginResponse();
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setEmail(user.getEmail());
        resp.setNickname(user.getNickname());
        resp.setAvatar(user.getAvatar());
        resp.setSignature(user.getSignature());
        resp.setAge(user.getAge());
        resp.setAddress(user.getAddress());
        resp.setStatus(user.getStatus());
        resp.setRoles(roleCodes);
        resp.setPermissions(new HashSet<>(perms));
        return resp;
    }

    @Override
    @Transactional
    public LoginResponse updateProfile(Long userId, ProfileUpdateRequest req) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        // 只更新请求中带了的字段(null = 不修改)
        if (req.getNickname() != null) user.setNickname(req.getNickname().trim());
        if (req.getAvatar() != null) user.setAvatar(req.getAvatar().trim());
        if (req.getSignature() != null) user.setSignature(req.getSignature().trim());
        if (req.getEmail() != null) {
            String email = req.getEmail().trim();
            userRepository.findByEmail(email)
                .filter(u -> !u.getId().equals(userId))
                .ifPresent(u -> { throw new BusinessException(409, "邮箱已被其他账号使用"); });
            user.setEmail(email);
        }
        if (req.getAge() != null) user.setAge(req.getAge());
        if (req.getAddress() != null) user.setAddress(req.getAddress().trim());

        userRepository.save(user);
        return me(userId);
    }

    /** 头像允许的图片类型 */
    private static final Set<String> AVATAR_EXTS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final long AVATAR_MAX_BYTES = 2 * 1024 * 1024; // 2MB

    @Override
    @Transactional
    public String updateAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的图片");
        }
        if (file.getSize() > AVATAR_MAX_BYTES) {
            throw new BusinessException(400, "头像图片不能超过 2MB");
        }

        // 只按白名单扩展名放行(文件名取原始扩展名小写)
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase() : "";
        if (!AVATAR_EXTS.contains(ext)) {
            throw new BusinessException(400, "仅支持 jpg / jpeg / png / gif / webp 图片");
        }

        // 校验文件内容魔数(防止改扩展名伪装,如 .png 实为 HTML)
        try (java.io.InputStream in = file.getInputStream()) {
            if (!matchesMagic(in.readNBytes(12), ext)) {
                throw new BusinessException(400, "文件内容与图片格式不符");
            }
        } catch (IOException e) {
            throw new BusinessException(400, "读取上传文件失败");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        // 落盘 data/uploads(相对运行目录),文件名随机化避免覆盖/伪造
        try {
            Path dir = Path.of("data", "uploads");
            Files.createDirectories(dir);
            String filename = "avatar_" + userId + "_" + System.currentTimeMillis() + "." + ext;
            Path target = dir.resolve(filename);
            file.transferTo(target.toAbsolutePath().toFile());

            String url = "/uploads/" + filename;
            user.setAvatar(url);
            userRepository.save(user);
            return url;
        } catch (IOException e) {
            throw new BusinessException(500, "头像保存失败: " + e.getMessage());
        }
    }

    /** 按魔数判断真实图片格式(与扩展名必须一致) */
    private boolean matchesMagic(byte[] h, String ext) {
        if (h == null || h.length < 4) return false;
        boolean jpg = (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
        boolean png = (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        boolean gif = h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
        boolean webp = h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
            && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        return switch (ext) {
            case "jpg", "jpeg" -> jpg;
            case "png" -> png;
            case "gif" -> gif;
            case "webp" -> webp;
            default -> false;
        };
    }

    private String getUsername(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"))
            .getUsername();
    }

    /**
     * 密码字段解密: 若加密密文存在则还原到明文 password; 否则校验明文非空。
     * LoginRequest 和 RegisterRequest 共享这套逻辑。
     */
    private void decryptPasswordField(java.util.function.Supplier<String> encryptedSupplier,
                                       java.util.function.Consumer<String> passwordSetter,
                                       java.util.function.BooleanSupplier isPlainEmpty,
                                       String emptyMessage) {
        String enc = encryptedSupplier.get();
        if (enc != null && !enc.isEmpty()) {
            String plain = rsaKeyProvider.decryptBase64(enc);
            passwordSetter.accept(plain);
            return;
        }
        if (isPlainEmpty.getAsBoolean()) {
            throw new BusinessException(400, emptyMessage);
        }
    }
}