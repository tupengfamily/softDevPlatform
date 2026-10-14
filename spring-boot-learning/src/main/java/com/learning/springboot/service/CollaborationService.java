package com.learning.springboot.service;

import com.learning.springboot.entity.Notification;
import com.learning.springboot.entity.User;
import com.learning.springboot.entity.WikiArticle;
import com.learning.springboot.entity.WikiCollaborator;
import com.learning.springboot.entity.WikiDirectory;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.NotificationRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.repository.WikiArticleRepository;
import com.learning.springboot.repository.WikiCollaboratorRepository;
import com.learning.springboot.repository.WikiDirectoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 协作申请 + 审批 服务
 * <p>
 * 职责:
 * 1. 申请人对资源(DIR/ART)发起协作申请(只读/编辑)
 * 2. 资源所有者审批(通过/拒绝)
 * 3. 申请人与审批方的双向通知
 * 4. 列出待我审批 / 我提交的申请
 * 5. 提供「资源有效权限」查询(article 优先 article 授权, 否则继承目录授权)
 * 6. 资源删除时联动清理协作者记录(由调用方显式调 cleanup)
 */
@Service
public class CollaborationService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationService.class);

    @Autowired private WikiCollaboratorRepository collabRepository;
    @Autowired private WikiDirectoryRepository directoryRepository;
    @Autowired private WikiArticleRepository articleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;

    // ==================== 发起申请 ====================

    /**
     * 申请人对资源发起协作申请
     * <p>
     * 规则:
     * - 不能申请自己的资源
     * - 已存在 PENDING 申请 → 拒绝重复
     * - 已存在 APPROVED 记录 → 拒绝重复(走"调整权限"流程,本次不实现)
     * - DIR 资源 → 校验目录存在且属于 ownerId
     * - ART 资源 → 校验文章存在且属于 ownerId
     */
    @Transactional
    public WikiCollaborator submitApplication(Long applicantId, String resourceType, Long resourceId,
                                               String permission, String reason) {
        User applicant = userRepository.findById(applicantId)
            .orElseThrow(() -> new BusinessException("用户不存在"));

        String ownerUserName;
        Long ownerId;
        String resourceName;

        if (WikiCollaborator.RESOURCE_DIR.equals(resourceType)) {
            WikiDirectory d = directoryRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(404, "目录不存在"));
            if (d.getUserId().equals(applicantId)) {
                throw new BusinessException(400, "不能申请自己拥有的目录");
            }
            ownerId = d.getUserId();
            resourceName = d.getName();
            // 校验所有者存在(可能已被禁用)
            User owner = userRepository.findById(ownerId).orElse(null);
            if (owner == null) throw new BusinessException(404, "资源所有者不存在");
            ownerUserName = owner.getUsername();
        } else if (WikiCollaborator.RESOURCE_ART.equals(resourceType)) {
            WikiArticle a = articleRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(404, "文章不存在"));
            if (a.getUserId().equals(applicantId)) {
                throw new BusinessException(400, "不能申请自己拥有的文章");
            }
            ownerId = a.getUserId();
            resourceName = a.getTitle();
            User owner = userRepository.findById(ownerId).orElse(null);
            if (owner == null) throw new BusinessException(404, "资源所有者不存在");
            ownerUserName = owner.getUsername();
        } else {
            throw new BusinessException(400, "非法资源类型: " + resourceType);
        }

        if (!WikiCollaborator.PERM_READ.equals(permission) && !WikiCollaborator.PERM_EDIT.equals(permission)) {
            throw new BusinessException(400, "非法权限: " + permission);
        }

        // 查重
        List<WikiCollaborator> existing = collabRepository.findByResourceTypeAndResourceIdAndUserId(
            resourceType, resourceId, applicantId);
        for (WikiCollaborator c : existing) {
            if (WikiCollaborator.STATUS_PENDING.equals(c.getStatus())) {
                throw new BusinessException(400, "已有待审批的申请, 请勿重复提交");
            }
            if (WikiCollaborator.STATUS_APPROVED.equals(c.getStatus())) {
                throw new BusinessException(400, "你已经是该资源的协作者, 无需再申请");
            }
        }

        WikiCollaborator c = new WikiCollaborator();
        c.setResourceType(resourceType);
        c.setResourceId(resourceId);
        c.setResourceName(resourceName);
        c.setOwnerId(ownerId);
        c.setUserId(applicantId);
        c.setUserName(applicant.getUsername());
        c.setPermission(permission);
        c.setStatus(WikiCollaborator.STATUS_PENDING);
        c.setReason(trimToNull(reason));
        c = collabRepository.save(c);

        // 通知所有者
        String permLabel = WikiCollaborator.PERM_EDIT.equals(permission) ? "编辑" : "只读";
        String resLabel = WikiCollaborator.RESOURCE_DIR.equals(resourceType) ? "目录" : "文章";
        notificationRepository.save(new Notification(
            ownerId,
            Notification.TYPE_COLLAB_REQUEST,
            "协作申请待审批",
            String.format("%s 申请以「%s」权限访问你的%s「%s」", applicant.getUsername(), permLabel, resLabel, resourceName),
            Notification.REL_WIKI_DIR.equals(resourceType) ? Notification.REL_WIKI_DIR : Notification.REL_WIKI_ART,
            resourceId
        ));
        log.info("[Collab] 申请提交: applicant={} {} {}(id={}) perm={}", applicantId, resourceType, resourceName, resourceId, permission);
        return c;
    }

    /** 申请人撤销自己的 PENDING 申请 */
    @Transactional
    public void cancelApplication(Long applicantId, Long collabId) {
        WikiCollaborator c = collabRepository.findById(collabId)
            .orElseThrow(() -> new BusinessException(404, "申请不存在"));
        if (!c.getUserId().equals(applicantId)) {
            throw new BusinessException(403, "只能撤销自己的申请");
        }
        if (!WikiCollaborator.STATUS_PENDING.equals(c.getStatus())) {
            throw new BusinessException(400, "仅可撤销待审批状态的申请");
        }
        c.setStatus(WikiCollaborator.STATUS_CANCELED);
        c.setHandleComment("申请人撤销");
        collabRepository.save(c);
        log.info("[Collab] 申请撤销: id={} applicant={}", collabId, applicantId);
    }

    // ==================== 审批 ====================

    /** 审批通过 */
    @Transactional
    public WikiCollaborator approveApplication(Long ownerId, Long collabId, String comment) {
        return doHandle(ownerId, collabId, true, comment);
    }

    /** 审批拒绝 */
    @Transactional
    public WikiCollaborator rejectApplication(Long ownerId, Long collabId, String comment) {
        return doHandle(ownerId, collabId, false, comment);
    }

    private WikiCollaborator doHandle(Long ownerId, Long collabId, boolean approve, String comment) {
        WikiCollaborator c = collabRepository.findById(collabId)
            .orElseThrow(() -> new BusinessException(404, "申请不存在"));
        if (!c.getOwnerId().equals(ownerId)) {
            throw new BusinessException(403, "仅资源所有者可审批");
        }
        if (!WikiCollaborator.STATUS_PENDING.equals(c.getStatus())) {
            throw new BusinessException(400, "该申请已处理, 无需重复审批");
        }
        c.setStatus(approve ? WikiCollaborator.STATUS_APPROVED : WikiCollaborator.STATUS_REJECTED);
        c.setHandleComment(trimToNull(comment));
        c = collabRepository.save(c);

        // 通知申请人
        String ownerName = userRepository.findById(ownerId).map(User::getUsername).orElse("所有者");
        String resLabel = WikiCollaborator.RESOURCE_DIR.equals(c.getResourceType()) ? "目录" : "文章";
        String permLabel = WikiCollaborator.PERM_EDIT.equals(c.getPermission()) ? "编辑" : "只读";
        String title = approve ? "协作申请已通过" : "协作申请被拒绝";
        String content = approve
            ? String.format("%s 已批准你以「%s」权限访问%s「%s」, 可在 Wiki 中查看", ownerName, permLabel, resLabel, c.getResourceName())
            : String.format("%s 拒绝了你的「%s」权限申请(%s「%s」)", ownerName, permLabel, resLabel, c.getResourceName());
        notificationRepository.save(new Notification(
            c.getUserId(),
            Notification.TYPE_COLLAB_RESULT,
            title,
            content,
            WikiCollaborator.RESOURCE_DIR.equals(c.getResourceType()) ? Notification.REL_WIKI_DIR : Notification.REL_WIKI_ART,
            c.getResourceId()
        ));
        log.info("[Collab] 审批{} id={} owner={} applicant={}", approve ? "通过" : "拒绝", collabId, ownerId, c.getUserId());
        return c;
    }

    // ==================== 列表查询 ====================

    /** 待我审批的申请(资源所有者视角) */
    public List<Map<String, Object>> listPendingForOwner(Long ownerId) {
        List<WikiCollaborator> list = collabRepository.findByOwnerIdAndStatusOrderByCreateTimeDesc(
            ownerId, WikiCollaborator.STATUS_PENDING);
        return list.stream().map(this::toVO).toList();
    }

    /** 我提交的所有申请(申请人视角) */
    public List<Map<String, Object>> listMineForApplicant(Long applicantId) {
        List<WikiCollaborator> list = collabRepository.findByUserIdOrderByCreateTimeDesc(applicantId);
        return list.stream().map(this::toVO).toList();
    }

    /** 我作为协作者已通过的资源列表(用于 Wiki 树中"共享给我") */
    public List<Map<String, Object>> listSharedToMe(Long userId) {
        List<WikiCollaborator> list = collabRepository.findByUserIdAndStatus(
            userId, WikiCollaborator.STATUS_APPROVED);
        return list.stream().map(c -> {
            Map<String, Object> m = toVO(c);
            m.put("name", c.getResourceName());
            m.put("permission", c.getPermission());
            m.put("ownerId", c.getOwnerId());
            return m;
        }).toList();
    }

    /** 资源上的协作者列表(所有者管理) */
    public List<Map<String, Object>> listCollaboratorsOfResource(String resourceType, Long resourceId) {
        return collabRepository.findByResourceTypeAndResourceId(resourceType, resourceId)
            .stream().map(this::toVO).toList();
    }

    /** 资源所有者的待审批 + 已处理历史(审批中心面板) */
    public List<Map<String, Object>> listOwnerInbox(Long ownerId) {
        List<WikiCollaborator> all = new ArrayList<>();
        // PENDING + 历史(已通过/已拒绝) 一起返回(在内存里按时间排序)
        all.addAll(collabRepository.findByOwnerIdAndStatusOrderByCreateTimeDesc(ownerId, WikiCollaborator.STATUS_APPROVED));
        all.addAll(collabRepository.findByOwnerIdAndStatusOrderByCreateTimeDesc(ownerId, WikiCollaborator.STATUS_REJECTED));
        all.addAll(collabRepository.findByOwnerIdAndStatusOrderByCreateTimeDesc(ownerId, WikiCollaborator.STATUS_PENDING));
        all.sort((a, b) -> {
            // PENDING 置顶, 然后按 createTime desc
            boolean pa = WikiCollaborator.STATUS_PENDING.equals(a.getStatus());
            boolean pb = WikiCollaborator.STATUS_PENDING.equals(b.getStatus());
            if (pa != pb) return pa ? -1 : 1;
            return b.getCreateTime().compareTo(a.getCreateTime());
        });
        return all.stream().map(this::toVO).toList();
    }

    // ==================== 权限解析 ====================

    /**
     * 查用户对指定文章的有效权限(article 自身优先, 否则继承目录)
     * <p>
     * 返回 "EDIT" / "READ" / null(无权限)
     */
    public String effectiveArticlePermission(Long userId, Long articleId) {
        if (userId == null || articleId == null) return null;
        // 1. 文章自身 owner 直接有 EDIT
        WikiArticle a = articleRepository.findById(articleId).orElse(null);
        if (a == null) return null;
        if (a.getUserId().equals(userId)) return WikiCollaborator.PERM_EDIT;

        // 2. 文章层显式授权
        List<WikiCollaborator> artCollabs = collabRepository.findByResourceTypeAndResourceIdAndStatus(
            WikiCollaborator.RESOURCE_ART, articleId, WikiCollaborator.STATUS_APPROVED);
        for (WikiCollaborator c : artCollabs) {
            if (c.getUserId().equals(userId)) return c.getPermission();
        }

        // 3. 继承目录层授权
        return effectiveDirectoryPermission(userId, a.getDirectoryId());
    }

    /** 查用户对指定目录的有效权限(不递归向上, 目录层只看本目录 + 子目录的显式授权) */
    public String effectiveDirectoryPermission(Long userId, Long directoryId) {
        if (userId == null || directoryId == null) return null;
        WikiDirectory d = directoryRepository.findById(directoryId).orElse(null);
        if (d == null) return null;
        if (d.getUserId().equals(userId)) return WikiCollaborator.PERM_EDIT;

        List<WikiCollaborator> dirCollabs = collabRepository.findByResourceTypeAndResourceIdAndStatus(
            WikiCollaborator.RESOURCE_DIR, directoryId, WikiCollaborator.STATUS_APPROVED);
        for (WikiCollaborator c : dirCollabs) {
            if (c.getUserId().equals(userId)) return c.getPermission();
        }
        // 4. 继承父目录授权(向上查一级; 实际场景基本够用)
        if (d.getParentId() != null) {
            return effectiveDirectoryPermission(userId, d.getParentId());
        }
        return null;
    }

    // ==================== 资源清理 ====================

    /** 资源被删除时清理(目录/文章) */
    @Transactional
    public void cleanupResource(String resourceType, Long resourceId) {
        collabRepository.deleteByResourceTypeAndResourceId(resourceType, resourceId);
    }

    // ==================== 工具 ====================

    private Map<String, Object> toVO(WikiCollaborator c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("resourceType", c.getResourceType());
        m.put("resourceId", c.getResourceId());
        m.put("resourceName", c.getResourceName());
        m.put("ownerId", c.getOwnerId());
        m.put("userId", c.getUserId());
        m.put("userName", c.getUserName());
        m.put("permission", c.getPermission());
        m.put("status", c.getStatus());
        m.put("reason", c.getReason());
        m.put("handleComment", c.getHandleComment());
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        return m;
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
