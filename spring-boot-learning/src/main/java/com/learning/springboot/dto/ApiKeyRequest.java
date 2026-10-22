package com.learning.springboot.dto;

/**
 * API Key 新增/修改请求
 *
 * <p>字段名与前端表单一一对应。{@code secret} 传 null / 空串表示「不改动密钥本体」。
 */
public class ApiKeyRequest {

    /** 业务唯一键, 例如 tianditu.serverKey */
    private String keyCode;

    /** 显示名称 */
    private String displayName;

    /** 分组/提供方 */
    private String provider;

    /** 密钥明文(仅写入方向使用, 响应中永不回显) */
    private String secret;

    /** 管理员备注 */
    private String secretHint;

    /** 1=启用 0=停用 */
    private Boolean enabled;

    public String getKeyCode() { return keyCode; }
    public void setKeyCode(String keyCode) { this.keyCode = keyCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getSecretHint() { return secretHint; }
    public void setSecretHint(String secretHint) { this.secretHint = secretHint; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
