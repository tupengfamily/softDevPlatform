package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 学习章节实体(课程树的中间层: 阶段 -> 章节 -> 课时)
 * <p>
 * 一个阶段(如"核心基础")包含多个章节(如"面向对象"),
 * 一个章节包含多个课时(LearningLesson)。
 */
@Entity
@Table(name = "t_learning_chapter")
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

    public LearningChapter() {}

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

    @Override
    public String toString() {
        return "LearningChapter{id=" + id + ", stageNo=" + stageNo
                + ", chapterNo=" + chapterNo + ", title='" + title + "'}";
    }
}
