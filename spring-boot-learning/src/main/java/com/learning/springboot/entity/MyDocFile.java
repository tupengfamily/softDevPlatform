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
 * 个人文档文件元数据
 * <p>
 * 二进制本体不落库(SQLite 扛不住大 BLOB), 只存磁盘相对定位 + 元信息:
 * 实际路径 = data/my-docs/{userId}/{storageKey}
 * <p>
 * 支持三种类型(见 {@link #docType}): word(.docx) / excel(.xlsx) / ppt(.pptx)。
 */
@Entity
@Table(name = "t_my_doc_file")
public class MyDocFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 所属目录(空 = 根目录) */
    @Column(name = "directory_id")
    private Long directoryId;

    /** 显示名(不含扩展名) */
    @Column(nullable = false, length = 200)
    private String name;

    /** 扩展名: docx / xlsx / pptx */
    @Column(nullable = false, length = 10)
    private String ext;

    /** 业务类型: word / excel / ppt */
    @Column(name = "doc_type", nullable = false, length = 10)
    private String docType;

    /** 磁盘文件名(UUID + 扩展名, 防路径注入/覆盖/中文名乱码) */
    @Column(name = "storage_key", nullable = false, length = 120)
    private String storageKey;

    /** 字节数 */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes = 0L;

    /** 同级排序序号 */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo = 0;

    /** 保存版本号(每次在线保存 +1) */
    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.sizeBytes == null) this.sizeBytes = 0L;
        if (this.sortNo == null) this.sortNo = 0;
        if (this.version == null) this.version = 1;
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getExt() { return ext; }
    public void setExt(String ext) { this.ext = ext; }

    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
