package com.learning.springboot.dto;

import java.time.LocalDateTime;

/**
 * 站内消息输出 VO(消息中心列表 / 头部铃铛面板)
 */
public class NotificationDTO {

    private Long id;
    /** 类型: SYSTEM / REPLY / TASK */
    private String type;
    /** 类型中文名: 系统通知 / 回复通知 / 任务通知 */
    private String typeLabel;
    private String title;
    private String content;
    /** 关联类型: POST / LESSON,可空 */
    private String relatedType;
    /** 关联 id,可空 */
    private Long relatedId;
    private Boolean read;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTypeLabel() { return typeLabel; }
    public void setTypeLabel(String typeLabel) { this.typeLabel = typeLabel; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRelatedType() { return relatedType; }
    public void setRelatedType(String relatedType) { this.relatedType = relatedType; }

    public Long getRelatedId() { return relatedId; }
    public void setRelatedId(Long relatedId) { this.relatedId = relatedId; }

    public Boolean getRead() { return read; }
    public void setRead(Boolean read) { this.read = read; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
