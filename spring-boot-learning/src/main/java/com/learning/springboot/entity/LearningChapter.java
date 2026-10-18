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

import java.time.LocalDateTime;

/**
 * 学习章节实体(课程树的中间层: 阶段 -> 章节 -> 课时)
 * <p>
 * 一个阶段(如"核心基础")包含多个章节(如"面向对象"),
 * 一个章节包含多个课时(LearningLesson)。
 */
@Entity
@Table(name = "t_learning_chapter", indexes = {
    // 课程树按 (subject_id, stage_no, chapter_no) 排序: 学习中心/看板
    @Index(name = "idx_subject_stage_chapter", columnList = "subject_id, stage_no, chapter_no"),
    // 按科目取章节
    @Index(name = "idx_subject", columnList = "subject_id")
})
public class LearningChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属科目(语言)ID,关联 t_learning_subject;空=默认科目(Java) */
    @Column(name = "subject_id")
    private Long subjectId;

    /** 阶段编号: 1=入门基础 2=核心基础 3=进阶特性 4=综合实战 */
    @Column(name = "stage_no", nullable = false)
    private Integer stageNo;

    /** 阶段名称,如"核心基础" */
    @Column(name = "stage_name", nullable = false, length = 50)
    private String stageName;

    /** 章节序号(全局递增 1..N) */
    @Column(name = "chapter_no", nullable = false)
    private Integer chapterNo;

    /** 章节标题,如"面向对象(OOP)" */
    @Column(nullable = false, length = 100)
    private String title;

    /** 章节简介 */
    @Column(length = 500)
    private String description;

    /** 对应示例代码位置,如 "oop/*" */
    @Column(name = "code_path", length = 200)
    private String codePath;

    /** 预计学习时长(小时) */
    @Column(name = "estimated_hours")
    private Integer estimatedHours;

    /** 归档(下架)标记:true 时不出现在课程树与统计中,内容与学习进度保留 */
    @Column(nullable = false)
    private Boolean archived = false;

    /**
     * 前置章节 ID 列表(JSON 数组, 存本学科内的其他章节 id)。
     * 当前章节只有在该列表里所有章节都被用户标记为"已学"后才能进入。
     * null/空 = 无前置, 默认解锁。
     * <p>
     * 例: 进阶章的前置 = 基础阶段的所有章 id, 用户学完基础才能开进阶。
     * 存为 JSON 字符串避免新增关联表(本项目无关系需求,只需在课程树接口里判是否锁定)。
     */
    @Column(name = "prerequisites", columnDefinition = "text")
    private String prerequisites;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 最后更新时间 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public LearningChapter() {}

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
        if (archived == null) archived = false;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public Integer getStageNo() { return stageNo; }
    public void setStageNo(Integer stageNo) { this.stageNo = stageNo; }

    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }

    public Integer getChapterNo() { return chapterNo; }
    public void setChapterNo(Integer chapterNo) { this.chapterNo = chapterNo; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCodePath() { return codePath; }
    public void setCodePath(String codePath) { this.codePath = codePath; }

    public Integer getEstimatedHours() { return estimatedHours; }
    public void setEstimatedHours(Integer estimatedHours) { this.estimatedHours = estimatedHours; }

    public Boolean getArchived() { return archived; }
    public void setArchived(Boolean archived) { this.archived = archived; }

    public String getPrerequisites() { return prerequisites; }
    public void setPrerequisites(String prerequisites) { this.prerequisites = prerequisites; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "LearningChapter{id=" + id + ", stageNo=" + stageNo
                + ", chapterNo=" + chapterNo + ", title='" + title + "'}";
    }
}
