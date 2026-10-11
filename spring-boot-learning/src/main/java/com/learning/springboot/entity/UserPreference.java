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
 * 用户学习偏好实体
 * <p>
 * 记录用户"正在学习的语言(科目)",用于:
 * - 头像旁语言选择框的记忆(跨设备、跨浏览器生效)
 * - 下次进入平台默认展示该语言的学习内容
 * - 管理员看板的"学习偏好"维度
 */
@Entity
@Table(name = "t_user_preference")
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 id(一人一条) */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 偏好科目(语言)id,t_learning_subject.id;空表示未设置 */
    @Column(name = "preferred_subject_id")
    private Long preferredSubjectId;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 更新时间 */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public UserPreference() {}

    public UserPreference(Long userId, Long preferredSubjectId) {
        this.userId = userId;
        this.preferredSubjectId = preferredSubjectId;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getPreferredSubjectId() { return preferredSubjectId; }
    public void setPreferredSubjectId(Long preferredSubjectId) { this.preferredSubjectId = preferredSubjectId; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
