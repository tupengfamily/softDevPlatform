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
 * 用户反馈实体
 * <p>
 * 三类反馈:
 * - RATING:    系统评分(1~5 星 + 评价内容)
 * - BUG:       问题反馈(标题 + 复现描述,可留联系方式)
 * - SUGGESTION: 功能建议(标题 + 建议内容)
 * 每条反馈可携带 source(来源页面),便于管理员定位反馈出处。
 * 状态流转: OPEN(待处理) -> PROCESSING(处理中) -> DONE(已处理)
 */
@Entity
@Table(name = "t_feedback")
public class Feedback {

    /** 反馈类型: RATING=评分, BUG=问题反馈, SUGGESTION=功能建议 */
    public static final String TYPE_RATING = "RATING";
    public static final String TYPE_BUG = "BUG";
    public static final String TYPE_SUGGESTION = "SUGGESTION";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 50, nullable = false)
    private String username;

    @Column(length = 10, nullable = false)
    private String type;

    /** 评分(仅 RATING 类型,1~5) */
    private Integer score;

    @Column(length = 100)
    private String title;

    @Column(length = 2000, nullable = false)
    private String content;

    /** 联系方式(可选,便于回复 bug) */
    @Column(length = 100)
    private String contact;

    /** 来源页面(自动携带: 页面名称 | 上下文 | 路由路径),便于管理员定位反馈出处 */
    @Column(name = "source", length = 300)
    private String source;

    @Column(length = 10, nullable = false)
    private String status = STATUS_OPEN;

    /** 管理员处理备注 */
    @Column(name = "admin_reply", length = 2000)
    private String adminReply;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.status == null) this.status = STATUS_OPEN;
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

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAdminReply() { return adminReply; }
    public void setAdminReply(String adminReply) { this.adminReply = adminReply; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
