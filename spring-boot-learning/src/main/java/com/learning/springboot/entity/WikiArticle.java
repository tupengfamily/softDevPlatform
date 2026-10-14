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
 * 个人 Wiki 文章(挂在目录下,可排序;可生成分享令牌供他人只读查看)
 */
@Entity
@Table(name = "t_wiki_article")
public class WikiArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 所属目录 ID */
    @Column(name = "directory_id", nullable = false)
    private Long directoryId;

    @Column(nullable = false, length = 200)
    private String title;

    /** 正文(轻量 Markdown / 富文本; 导入文档转换后可能较长) */
    @Column(nullable = false, length = 200000)
    private String content;

    /** 内容格式: markdown / richtext */
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = "markdown";

    /** 排序序号(升序,目录内) */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo = 0;

    /** 分享令牌(空=未开启分享;有值时 /wiki/share/{token} 可访问) */
    @Column(name = "share_token", length = 64, unique = true)
    private String shareToken;

    /**
     * 分享是否要求登录后才能查看(false=任何人凭链接可看, 默认;true=必须登录)。
     * columnDefinition 显式带 default 0: SQLite 不允许给已有数据的表加"NOT NULL 且无默认值"的列
     * (否则 Hibernate ddl-auto 增量加列会静默失败)。
     */
    @Column(name = "share_require_login", columnDefinition = "boolean not null default 0")
    private Boolean shareRequireLogin = false;

    /** 分享被查看次数 */
    @Column(name = "view_count", nullable = false)
    private Long viewCount = 0L;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.viewCount == null) this.viewCount = 0L;
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

    public Long getDirectoryId() { return directoryId; }
    public void setDirectoryId(Long directoryId) { this.directoryId = directoryId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public String getShareToken() { return shareToken; }
    public void setShareToken(String shareToken) { this.shareToken = shareToken; }

    public Boolean getShareRequireLogin() { return shareRequireLogin == null ? Boolean.FALSE : shareRequireLogin; }
    public void setShareRequireLogin(Boolean shareRequireLogin) { this.shareRequireLogin = shareRequireLogin; }

    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
