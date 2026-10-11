package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 课时实体(课程树的叶子节点,即"一节课")
 * <p>
 * content 采用轻量 Markdown 语法书写:
 * "## " 开头为小标题, "- " 开头为列表项, 其余为段落。
 */
@Entity
@Table(name = "t_learning_lesson")
public class LearningLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属章节 id(t_learning_chapter.id) */
    @Column(name = "chapter_id", nullable = false)
    private Long chapterId;

    /** 课时在章节内的序号 */
    @Column(name = "lesson_no", nullable = false)
    private Integer lessonNo;

    /** 课时标题 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 一句话摘要(目录展示用) */
    @Column(length = 300)
    private String summary;

    /** 课时正文(轻量 Markdown) */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 示例代码(可为空) */
    @Column(columnDefinition = "TEXT")
    private String codeExample;

    /** 示例代码类型: java(Java 沙箱) / sql(SQL 沙箱), 默认 java */
    private String codeType;

    /** 难度: 1=简单 2=中等 3=较难 */
    @Column(nullable = false)
    private Integer difficulty = 1;

    /** 预计学习分钟数 */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 15;

    public LearningLesson() {}

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

    public Integer getLessonNo() { return lessonNo; }
    public void setLessonNo(Integer lessonNo) { this.lessonNo = lessonNo; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCodeExample() { return codeExample; }
    public void setCodeExample(String codeExample) { this.codeExample = codeExample; }
    public String getCodeType() { return codeType; }
    public void setCodeType(String codeType) { this.codeType = codeType; }

    public Integer getDifficulty() { return difficulty; }
    public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    @Override
    public String toString() {
        return "LearningLesson{id=" + id + ", chapterId=" + chapterId
                + ", lessonNo=" + lessonNo + ", title='" + title + "'}";
    }
}
