package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 个人数据导入导出记录
 * <p>
 * 每次导出/导入生成一条记录: 类型(EXPORT/IMPORT)、内容快照(JSON)、大小、说明、时间;
 * 支持事后查看与下载。
 */
@Entity
@Table(name = "t_user_data_record")
public class UserDataRecord {

    public static final String TYPE_EXPORT = "EXPORT";
    public static final String TYPE_IMPORT = "IMPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 50)
    private String username;

    /** 记录类型: EXPORT=导出, IMPORT=导入 */
    @Column(nullable = false, length = 10)
    private String type;

    /** 内容快照(JSON 文本),供查看/下载 */
    @Column(length = 2000000)
    private String content;

    /** 内容字节大小 */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes = 0L;

    /** 说明(导出: 数据概况;导入: 结果统计) */
    @Column(length = 500)
    private String summary;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
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

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
