package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 用户补充节点 — 用户在课程章节下自建的"章节/子节点"
 * <p>
 * 用于记录与补充官方课程缺失的内容,支持无限级子节点(树形)。
 * 挂载位置三选一:
 * - subjectId + chapterId  : 挂在官方章节下(子节点可继续挂在节点下)
 * - subjectId + parentNodeId: 挂在其他补充节点下(任意层级)
 * - 仅 subjectId           : 用户自建的"补充章节"(科目下的根节点)
 * <p>
 * 内容支持两种格式: markdown / richtext(Quill HTML)。
 * 所有登录用户可查看与新增;作者或管理员可编辑/删除。
 */
@Entity
@Table(name = "t_supplement_node")
public class SupplementNode {

    /** 内容格式: Markdown */
    public static final String TYPE_MARKDOWN = "markdown";
    /** 内容格式: 富文本(Quill HTML) */
    public static final String TYPE_RICHTEXT = "richtext";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属科目(语言)ID */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** 挂载的官方章节 ID(可空) */
    @Column(name = "chapter_id")
    private Long chapterId;

    /** 父补充节点 ID(可空;与 chapterId 互斥,父节点存在时以 parentNodeId 为准) */
    @Column(name = "parent_node_id")
    private Long parentNodeId;

    /** 标题 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 正文(Markdown 或 Quill HTML) */
    @Column(nullable = false, length = 20000)
    private String content;

    /** 内容格式: markdown / richtext */
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = TYPE_MARKDOWN;

    /** 作者用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 作者用户名(冗余,便于展示) */
    @Column(name = "username", length = 50)
    private String username;

    /** 同级排序号(小的在前) */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public SupplementNode() {}

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

    public Long getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(Long parentNodeId) { this.parentNodeId = parentNodeId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "SupplementNode{id=" + id + ", title='" + title + "', userId=" + userId + "}";
    }
}
