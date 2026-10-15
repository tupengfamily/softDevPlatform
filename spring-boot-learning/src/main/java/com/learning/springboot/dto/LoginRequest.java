package com.learning.springboot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求
 *
 * <p>支持两种密码传输方式(优先用 {@code encryptedPassword}):
 * <ol>
 *   <li>明文密码 {@code password}: 仅用于向后兼容(开发调试 / 老客户端); 生产环境建议 RSA 加密</li>
 *   <li>RSA 加密密码 {@code encryptedPassword}: 前端用 {@code GET /api/auth/public-key} 拿公钥,
 *       客户端用 RSA-OAEP-SHA256 加密后 Base64; 后端用本地私钥还原明文后再 bcrypt 校验</li>
 * </ol>
 */
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 明文密码(向后兼容, 仅在没有 encryptedPassword 时使用) */
    private String password;

    /** RSA 公钥加密后的 Base64 密文(优先) */
    private String encryptedPassword;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }
}