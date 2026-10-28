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
 * 课时实体(课程树的叶子节点,即"一节课")
 * <p>
 * content 采用轻量 Markdown 语法书写:
 * "## " 开头为小标题, "- " 开头为列表项, 其余为段落。
 */
@Entity
@Table(name = "t_learning_lesson", indexes = {
    // 章节内课时排序: WHERE chapter_id=? ORDER BY lesson_no
    @Index(name = "idx_chapter_lessonNo", columnList = "chapter_id, lesson_no"),
    // 全量课程树按章节排序
    @Index(name = "idx_chapter", columnList = "chapter_id")
})
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

    /** 课时正文(轻量 Markdown,中文为主) */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 课时英文正文(可为空;为空时前端回落到 content) */
    @Column(name = "content_en", columnDefinition = "TEXT")
    private String contentEn;

    /** 示例代码(可为空) */
    @Column(columnDefinition = "TEXT")
    private String codeExample;

    /** 示例代码英文版(可为空;前端按 locale 切换) */
    @Column(name = "code_example_en", columnDefinition = "TEXT")
    private String codeExampleEn;

    /** 示例代码类型: java(Java 沙箱) / sql(SQL 沙箱), 默认 java */
    private String codeType;

    /**
     * 农业科学概念标识（2026-10-28 P2）。
     *
     * <p>🔴 <b>为什么单独加一个字段，而不是复用 {@code codePath}</b>：
     * {@code codePath} 在前端已经被当作「练习代码位置」展示与复制
     * （{@code LessonDetail.vue} 的 copyPath 按钮），
     * 塞进概念 ID 会让玩家复制到一串无意义的东西，而且语义完全错位。
     *
     * <p>🔴 <b>为什么必须是稳定标识而不是标题/序号</b>：
     * 本字段是「农事动作 → 概念 → 课时」的绑定键。
     * 如果用课时标题绑定，改一次标题就断链；
     * 如果用章节序号绑定，插入一章就全乱。
     * 概念 ID（{@code AgriConcept}）是<b>一经发布不可改</b>的常量。
     *
     * <p>非农业课���为 {@code null}。
     */
    @Column(name = "concept_id", length = 32)
    private String conceptId;

    /** 难度: 1=简单 2=中等 3=较难 */
    @Column(nullable = false)
    private Integer difficulty = 1;

    /** 预计学习分钟数 */
    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes = 15;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 最后更新时间(用于审计) */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    public LearningLesson() {}

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }

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

    public String getContentEn() { return contentEn; }
    public void setContentEn(String contentEn) { this.contentEn = contentEn; }

    public String getCodeExample() { return codeExample; }
    public void setCodeExample(String codeExample) { this.codeExample = codeExample; }

    public String getCodeExampleEn() { return codeExampleEn; }
    public void setCodeExampleEn(String codeExampleEn) { this.codeExampleEn = codeExampleEn; }
    public String getCodeType() { return codeType; }
    public void setCodeType(String codeType) { this.codeType = codeType; }

    public String getConceptId() { return conceptId; }
    public void setConceptId(String conceptId) { this.conceptId = conceptId; }

    public Integer getDifficulty() { return difficulty; }
    public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "LearningLesson{id=" + id + ", chapterId=" + chapterId
                + ", lessonNo=" + lessonNo + ", title='" + title + "'}";
    }
}
