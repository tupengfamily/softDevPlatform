package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Wiki 协作者关系(目录/文章级)
 * <p>
 * 字段语义:
 * - resourceType: DIR / ART (决定 resourceId 指向 t_wiki_directory / t_wiki_article)
 * - permission : READ / EDIT
 * - status     : PENDING(待审批) / APPROVED(已通过) / REJECTED(已拒绝) / CANCELED(申请人撤销)
 * <p>
 * 唯一约束: 同一用户在同一资源上只能有一条有效记录 (resourceType, resourceId, userId)
 * (注意: SQLite 不支持 partial unique,但应用层在创建前查重)
 */
@Entity
@Table(name = "t_wiki_collaborator", uniqueConstraints = {
    @UniqueConstraint(name = "uk_wiki_collab_resource_user", columnNames = {"resource_type", "resource_id", "user_id"})
})
public class WikiCollaborator {

    /** 资源类型: 目录 */
    public static final String RESOURCE_DIR = "DIR";
    /** 资源类型: 文章 */
    public static final String RESOURCE_ART = "ART";

    /** 权限: 只读 */
    public static final String PERM_READ = "READ";
    /** 权限: 编辑 */
    public static final String PERM_EDIT = "EDIT";

    /** 状态: 待审批 */
    public static final String STATUS_PENDING  = "PENDING";
    /** 状态: 已通过 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 状态: 已拒绝 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 状态: 申请人撤销 */
    public static final String STATUS_CANCELED = "CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_type", nullable = false, length = 10)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** 冗余存资源名(资源改名/删除后历史记录仍可读) */
    @Column(name = "resource_name", nullable = false, length = 200)
    private String resourceName;

    /** 资源所有者(冗余存, 用于通知与权限校验) */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 协作者用户ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 协作者用户名(冗余) */
    @Column(name = "user_name", nullable = false, length = 100)
    private String userName;

    /** 权限(READ / EDIT) */
    @Column(nullable = false, length = 10)
    private String permission;

    /** 状态 */
    @Column(nullable = false, length = 10)
    private String status;

    /** 申请理由 */
    @Column(length = 500)
    private String reason;

    /** 审批意见(同意/拒绝时的备注) */
    @Column(name = "handle_comment", length = 500)
    private String handleComment;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        if (updateTime == null) updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getHandleComment() { return handleComment; }
    public void setHandleComment(String handleComment) { this.handleComment = handleComment; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
