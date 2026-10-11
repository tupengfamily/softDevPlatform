package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 学习进度实体(记录"哪个用户学完了哪节课")
 * <p>
 * - (userId, lessonId) 唯一: 一节课只会有一条进度记录
 * - status: 1=已学习 (未学习的课时没有记录,天然区分"未开始")
 * - learnedAt: 完成时间,用于"最近学习"时间线
 */
@Entity
@Table(name = "t_lesson_progress", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_lesson", columnNames = {"user_id", "lesson_id"})
})
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

    /** 1=已学习 */
    @Column(nullable = false)
    private Integer status = 1;

    /** 首次完成时间 */
    @Column(name = "learned_at", nullable = false, updatable = false)
    private LocalDateTime learnedAt;

    /** 记录创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    public LessonProgress() {}

    public LessonProgress(Long userId, Long lessonId) {
        this.userId = userId;
        this.lessonId = lessonId;
        this.status = 1;
        this.learnedAt = LocalDateTime.now();
        this.createTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getLearnedAt() { return learnedAt; }
    public void setLearnedAt(LocalDateTime learnedAt) { this.learnedAt = learnedAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    @Override
    public String toString() {
        return "LessonProgress{id=" + id + ", userId=" + userId
                + ", lessonId=" + lessonId + ", status=" + status + "}";
    }
}
