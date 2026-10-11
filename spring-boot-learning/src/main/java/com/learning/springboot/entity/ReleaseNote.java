package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 版本更新公告(更新日志)实体
 * <p>
 * 每次发布版本时写入一条记录: 版本号 + 标题 + 更新内容。
 * 前端用它做两件事:
 * - 新版本公告弹窗(对比 localStorage 已读版本)
 * - 更新日志页(历史版本时间线)
 */
@Entity
@Table(name = "t_release_note")
public class ReleaseNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 版本号,如 2.1.0 */
    @Column(length = 30, nullable = false, unique = true)
    private String version;

    /** 公告标题 */
    @Column(length = 100, nullable = false)
    private String title;

    /** 更新内容(富文本 HTML, Quill 编辑器产出) */
    @Column(length = 20000, nullable = false)
    private String content;

    /** 发布日期 */
    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        if (this.releaseDate == null) this.releaseDate = LocalDate.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDate getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
