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
 * 个人文档目录(用户私有文件夹,支持多级子目录 + 排序)
 * <p>
 * 与 Wiki 目录的区别: Wiki 存的是"文章内容", 这里存的是"文件"(真实 .docx/.xlsx/.pptx 落盘)。
 */
@Entity
@Table(name = "t_my_doc_directory")
public class MyDocDirectory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 目录名 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 父目录 ID(空 = 顶级目录) */
    @Column(name = "parent_id")
    private Long parentId;

    /** 同级排序序号(升序) */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo = 0;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.sortNo == null) this.sortNo = 0;
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
