package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 学习科目(语言)实体 — 通用学习平台的"课程维度"
 * <p>
 * 平台不局限于 Java:后续可扩展 Python、JavaScript 等科目。
 * 科目由管理员在"学习配置"中维护(增删改、排序、启停),
 * 启用的科目会出现在学习中心顶部切换与学习看板的维度筛选中。
 */
@Entity
@Table(name = "t_learning_subject",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_code", columnNames = "code")
    },
    indexes = {
        // 学习中心取 (enabled, sort_no) 排序列表
        @Index(name = "idx_enabled_sort", columnList = "enabled, sort_no")
    }
)
public class LearningSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 科目编码,唯一,如 java / python / javascript */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** 科目名称,如 "Java" */
    @Column(nullable = false, length = 50)
    private String name;

    /** 科目简介 */
    @Column(length = 200)
    private String description;

    /** 排序号(小的在前) */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo = 0;

    /** 是否启用(停用后学习中心/看板不展示) */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 是否内置(内置科目不允许删除,避免课程数据孤儿) */
    @Column(nullable = false)
    private Boolean builtin = false;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 最后更新时间 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public LearningSubject() {}

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
        if (enabled == null) enabled = true;
        if (builtin == null) builtin = false;
        if (sortNo == null) sortNo = 0;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getBuiltin() { return builtin; }
    public void setBuiltin(Boolean builtin) { this.builtin = builtin; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "LearningSubject{id=" + id + ", code='" + code + "', name='" + name + "'}";
    }
}
