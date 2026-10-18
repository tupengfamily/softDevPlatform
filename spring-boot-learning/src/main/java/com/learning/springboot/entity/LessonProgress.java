package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 学习进度实体(记录"哪个用户学完了哪节课")
 * <p>
 * - (userId, lessonId) 唯一: 一节课只会有一条进度记录
 * - 记录存在 = 已学(未学习天然区分);progressPercent 提供 0-100 中间进度以支持"看到一半"等场景
 * - updatedAt 用于多端同步冲突检测
 */
@Entity
@Table(name = "t_lesson_progress",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_lesson", columnNames = {"user_id", "lesson_id"})
    },
    indexes = {
        // 看板 "按用户最近学习" / 我的学习中心按时间线: WHERE user_id=? ORDER BY learned_at DESC
        @Index(name = "idx_user_learnedAt", columnList = "user_id, learned_at DESC"),
        // 运维查询 / 章节级进度聚合: WHERE lesson_id=?
        @Index(name = "idx_lesson", columnList = "lesson_id")
    }
)
public class LessonProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 id */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 课时 id */
    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    /** 学习进度 0-100, >= 80 视为已学完(可前端定时上报/滚动到底时上报) */
    @Column(name = "progress_percent", nullable = false)
    private Integer progressPercent = 0;

    /** 首次完成时间(进度第一次 >= 80 时写入,后续不再更新) */
    @Column(name = "learned_at", updatable = false)
    private LocalDateTime learnedAt;

    /** 记录创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 最后更新时间(用于多端同步冲突检测 / 审计) */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public LessonProgress() {}

    public LessonProgress(Long userId, Long lessonId) {
        this.userId = userId;
        this.lessonId = lessonId;
        this.progressPercent = 0;
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
        if (progressPercent == null) progressPercent = 0;
        // 进度达到 100 时自动设 learned_at
        if (progressPercent != null && progressPercent >= 80 && learnedAt == null) {
            learnedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
        if (progressPercent != null && progressPercent >= 80 && learnedAt == null) {
            learnedAt = this.updateTime;
        }
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }

    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }

    public LocalDateTime getLearnedAt() { return learnedAt; }
    public void setLearnedAt(LocalDateTime learnedAt) { this.learnedAt = learnedAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "LessonProgress{id=" + id + ", userId=" + userId
                + ", lessonId=" + lessonId + ", progressPercent=" + progressPercent + "}";
    }
}
