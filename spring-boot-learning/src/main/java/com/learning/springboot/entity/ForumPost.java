package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 论坛帖子实体 — 用于用户之间的学习交流
 */
@Entity
@Table(name = "t_forum_post")
public class ForumPost {

    /** 内容格式: Markdown */
    public static final String TYPE_MARKDOWN = "markdown";
    /** 内容格式: 富文本(Quill HTML) */
    public static final String TYPE_RICHTEXT = "richtext";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联科目(语言)ID,可空=综合讨论 */
    @Column(name = "subject_id")
    private Long subjectId;

    /** 标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 正文(Markdown 或 Quill HTML) */
    @Column(nullable = false, length = 20000)
    private String content;

    /** 内容格式: markdown / richtext */
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = TYPE_RICHTEXT;

    /** 作者用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 作者用户名(冗余,便于展示) */
    @Column(name = "username", length = 50)
    private String username;

    /** 浏览数 */
    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    /** 回复数(冗余计数,发帖/删帖时维护) */
    @Column(name = "reply_count", nullable = false)
    private Integer replyCount = 0;

    /** 是否置顶 */
    @Column(nullable = false)
    private Boolean pinned = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public ForumPost() {}

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Integer getViewCount() { return viewCount; }
    public void setViewCount(Integer viewCount) { this.viewCount = viewCount; }

    public Integer getReplyCount() { return replyCount; }
    public void setReplyCount(Integer replyCount) { this.replyCount = replyCount; }

    public Boolean getPinned() { return pinned; }
    public void setPinned(Boolean pinned) { this.pinned = pinned; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "ForumPost{id=" + id + ", title='" + title + "', userId=" + userId + "}";
    }
}
