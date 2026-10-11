package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 论坛回复实体
 */
@Entity
@Table(name = "t_forum_reply")
public class ForumReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属帖子 ID */
    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** 作者用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 作者用户名(冗余,便于展示) */
    @Column(name = "username", length = 50)
    private String username;

    /** 回复内容(纯文本/轻量 Markdown,单条 5000 字内) */
    @Column(nullable = false, length = 5000)
    private String content;

    /** 内容格式: markdown / richtext */
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = "markdown";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ForumReply() {}

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "ForumReply{id=" + id + ", postId=" + postId + ", userId=" + userId + "}";
    }
}
