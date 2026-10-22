package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 第三方 API Key / 密钥配置
 *
 * <p>系统后续接入的各种外部服务(地图、LLM、支付、短信、对象存储……)的凭据统一存这里,
 * 由管理员在「系统管理 → API 密钥」里维护, 不再散落到 application.yml 或环境变量。
 *
 * <p><b>安全约定</b>:
 * <ul>
 *   <li>{@link #secretValue} 存的是 <b>密文</b>(AES-256-GCM), 任何查询接口都不会返回它;</li>
 *   <li>{@link #secretMask} 存掩码(如 {@code abcd****wxyz}), 供界面辨认「这是哪一把 key」;</li>
 *   <li>{@link #secretHint} 是给管理员看的自由备注(如「生产环境用这个」), <b>不参与加密</b>,
 *       因为它不含机密, 但也不该出现在日志里。</li>
 * </ul>
 */
@Entity
@Table(name = "t_api_key")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务唯一键, 例如 tianditu.serverKey、llm.deepseek.apiKey */
    @Column(name = "key_code", nullable = false, length = 100)
    private String keyCode;

    /** 显示名称, 例如「天地图服务端 Key」 */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** 分组/提供方, 例如「地图」「大模型」「支付」 */
    @Column(name = "provider", length = 60)
    private String provider;

    /** 密文(AES-256-GCM, base64) */
    @Column(name = "secret_value", length = 2000)
    private String secretValue;

    /** 掩码, 如 abcd****wxyz */
    @Column(name = "secret_mask", length = 60)
    private String secretMask;

    /** 管理员备注(不含机密, 明文存储便于检索) */
    @Column(name = "secret_hint", length = 300)
    private String secretHint;

    /** 1=启用 0=停用 */
    @Column(nullable = false)
    private Integer enabled = 1;

    /** 最近一次被业务读取的时间 */
    @Column(name = "last_used_time")
    private LocalDateTime lastUsedTime;

    /** 最近一次修改时间 */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 最后修改者(管理员用户名) */
    @Column(name = "update_by", length = 60)
    private String updateBy;

    public ApiKey() {}

    @PrePersist
    public void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKeyCode() { return keyCode; }
    public void setKeyCode(String keyCode) { this.keyCode = keyCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getSecretValue() { return secretValue; }
    public void setSecretValue(String secretValue) { this.secretValue = secretValue; }
    public String getSecretMask() { return secretMask; }
    public void setSecretMask(String secretMask) { this.secretMask = secretMask; }
    public String getSecretHint() { return secretHint; }
    public void setSecretHint(String secretHint) { this.secretHint = secretHint; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public LocalDateTime getLastUsedTime() { return lastUsedTime; }
    public void setLastUsedTime(LocalDateTime lastUsedTime) { this.lastUsedTime = lastUsedTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
