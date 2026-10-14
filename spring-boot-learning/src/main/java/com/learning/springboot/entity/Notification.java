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
 * 站内消息(消息中心)实体
 * <p>
 * 头部"消息中心"铃铛展示当前用户的系统消息,类型包括:
 * - SYSTEM: 系统通知(管理员发布、新版本公告等)
 * - REPLY:  论坛回复通知(有人回复了我的帖子)
 * - TASK:   任务完成通知(完成了某课时的学习)
 */
@Entity
@Table(name = "t_notification")
public class Notification {

    /** 消息类型: 系统通知 */
    public static final String TYPE_SYSTEM = "SYSTEM";
    /** 消息类型: 论坛回复通知 */
    public static final String TYPE_REPLY = "REPLY";
    /** 消息类型: 任务完成通知 */
    public static final String TYPE_TASK = "TASK";
    /** 消息类型: Wiki 协作者申请提交(发给资源所有者审批) */
    public static final String TYPE_COLLAB_REQUEST = "COLLAB_REQUEST";
    /** 消息类型: Wiki 协作申请审批结果(发给申请人) */
    public static final String TYPE_COLLAB_RESULT = "COLLAB_RESULT";

    /** 关联类型: 论坛帖子 */
    public static final String REL_POST = "POST";
    /** 关联类型: 课时 */
    public static final String REL_LESSON = "LESSON";
    /** 关联类型: Wiki 目录(申请单 relatedId 存目录 ID) */
    public static final String REL_WIKI_DIR = "WIKI_DIR";
    /** 关联类型: Wiki 文章(申请单 relatedId 存文章 ID) */
    public static final String REL_WIKI_ART = "WIKI_ART";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 接收用户 id */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 消息类型: SYSTEM / REPLY / TASK */
    @Column(nullable = false, length = 20)
    private String type;

    /** 标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 内容 */
    @Column(nullable = false, length = 2000)
    private String content;

    /** 关联类型: POST / LESSON,可空 */
    @Column(name = "related_type", length = 20)
    private String relatedType;

    /** 关联 id(帖子 id / 课时 id),可空 */
    @Column(name = "related_id")
    private Long relatedId;

    /** 是否已读 */
    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    public Notification() {}

    public Notification(Long userId, String type, String title, String content,
                        String relatedType, Long relatedId) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.relatedType = relatedType;
        this.relatedId = relatedId;
    }

    @PrePersist
    public void prePersist() {
        this.createTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRelatedType() { return relatedType; }
    public void setRelatedType(String relatedType) { this.relatedType = relatedType; }

    public Long getRelatedId() { return relatedId; }
    public void setRelatedId(Long relatedId) { this.relatedId = relatedId; }

    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
