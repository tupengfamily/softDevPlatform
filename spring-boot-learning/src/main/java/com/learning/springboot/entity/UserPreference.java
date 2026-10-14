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

    /** 界面语言偏好(zh-CN/en-US),空表示未设置(前端默认 zh-CN) */
    @Column(name = "locale", length = 10)
    private String locale;

    // ==================== 新手指引(onboarding)字段 ====================

    /** 学习目标: JOB(求职) / INTEREST(兴趣) / EXAM(考试) / PROJECT(项目) */
    @Column(name = "goal", length = 20)
    private String goal;

    /** 起点水平: BEGINNER(零基础) / HAS_OTHER(有其他语言基础) / HAS_JS(有 JS 基础) / INTERMEDIATE(中级) */
    @Column(name = "level", length = 20)
    private String level;

    /** 完成 onboarding 的时间(NULL = 未完成, 用于前端判断是否弹引导) */
    @Column(name = "onboarded_at")
    private LocalDateTime onboardedAt;

    /** 是否主动跳过(与 onboarded_at 互斥, 同时只能有一个非空) */
    @Column(name = "onboarding_skipped", columnDefinition = "boolean not null default 0")
    private Boolean onboardingSkipped = false;

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

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public LocalDateTime getOnboardedAt() { return onboardedAt; }
    public void setOnboardedAt(LocalDateTime onboardedAt) { this.onboardedAt = onboardedAt; }

    public Boolean getOnboardingSkipped() { return onboardingSkipped; }
    public void setOnboardingSkipped(Boolean onboardingSkipped) { this.onboardingSkipped = onboardingSkipped; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
