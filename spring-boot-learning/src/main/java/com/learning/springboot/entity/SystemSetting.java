package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 系统级 KV 设置(管理员可调), 区别于 Dict(给业务页标签文案用):
 * <ul>
 *   <li>Dict: 给前端展示用的字典项</li>
 *   <li>SystemSetting: 后端运行配置, 例如 myDoc.maxQuotaBytes</li>
 * </ul>
 * 用 String 存 value; 解析由调用方负责(本系统都是数字 / 字符串, 没有复杂结构)。
 */
@Entity
@Table(name = "t_system_setting")
public class SystemSetting {

    @Id
    @Column(name = "key_code", length = 64)
    private String key;

    @Column(name = "value_text", length = 500)
    private String value;

    @Column(length = 200)
    private String description;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public SystemSetting() {}

    public SystemSetting(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
    }

    @PrePersist
    @PreUpdate
    public void touch() { this.updateTime = LocalDateTime.now(); }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
