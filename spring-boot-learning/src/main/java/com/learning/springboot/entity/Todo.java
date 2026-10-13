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
 * 个人待办(我的待办: 学习计划/日常任务提醒)
 * <p>
 * 仅本人可见:所有查询/写操作都按 user_id 过滤。
 * priority: 0=低 1=中 2=高;status: 0=待办 1=已完成。
 */
@Entity
@Table(name = "t_todo")
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 待办内容 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 优先级: 0=低 1=中 2=高 */
    @Column(nullable = false)
    private Integer priority = 1;

    /** 状态: 0=待办 1=已完成 */
    @Column(nullable = false)
    private Integer status = 0;

    /** 截止日期(yyyy-MM-dd,可空) */
    @Column(name = "due_date", length = 10)
    private String dueDate;

    /** 完成时间 */
    @Column(name = "done_time")
    private LocalDateTime doneTime;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.priority == null) this.priority = 1;
        if (this.status == null) this.status = 0;
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

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }

    public LocalDateTime getDoneTime() { return doneTime; }
    public void setDoneTime(LocalDateTime doneTime) { this.doneTime = doneTime; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
