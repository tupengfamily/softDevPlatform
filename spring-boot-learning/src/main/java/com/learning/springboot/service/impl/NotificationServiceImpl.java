package com.learning.springboot.service.impl;

import com.learning.springboot.dto.NotificationDTO;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.Notification;
import com.learning.springboot.entity.User;
import com.learning.springboot.repository.NotificationRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 站内消息服务实现
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;

    // ==================== 查询 ====================

    @Override
    public PageResult<NotificationDTO> page(Long userId, String type, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(size, 1));
        String t = normalizeType(type);
        Page<Notification> result = (t == null)
            ? notificationRepository.findByUserIdOrderByCreateTimeDesc(userId, pageable)
            : notificationRepository.findByUserIdAndTypeOrderByCreateTimeDesc(userId, t, pageable);
        return PageResult.of(result.map(this::toDTO));
    }

    @Override
    public List<NotificationDTO> recent(Long userId) {
        return notificationRepository.findTop8ByUserIdOrderByCreateTimeDesc(userId)
            .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    // ==================== 已读 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long id) {
        int updated = notificationRepository.markRead(id, userId);
        if (updated == 0) {
            // 消息不存在、不属于当前用户、或已读(幂等容忍已读;不存在/越权才拒绝)
            boolean mine = notificationRepository.findById(id)
                .map(n -> n.getUserId().equals(userId))
                .orElse(false);
            if (!mine) {
                throw new NoSuchElementException("消息不存在: " + id);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long userId) {
        int updated = notificationRepository.markAllRead(userId);
        log.debug("用户 {} 全部标记已读 {} 条", userId, updated);
        return updated;
    }

    // ==================== 通知产生 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int sendSystemNotice(String title, String content) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("通知标题不能为空");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("通知内容不能为空");
        }
        List<User> users = userRepository.findAll().stream()
            .filter(u -> u.getStatus() == null || u.getStatus() == 1)   // 启用用户
            .collect(Collectors.toList());
        for (User u : users) {
            notificationRepository.save(new Notification(
                u.getId(), Notification.TYPE_SYSTEM, title.trim(), content.trim(), null, null));
        }
        log.info("系统通知《{}》已推送给 {} 位用户", title, users.size());
        return users.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notifyReply(Long postAuthorId, Long fromUserId, String fromUsername, String postTitle, Long postId) {
        if (postAuthorId == null || postAuthorId.equals(fromUserId)) {
            return;   // 自己回自己不通知
        }
        notificationRepository.save(new Notification(
            postAuthorId, Notification.TYPE_REPLY,
            "论坛新回复",
            String.format("%s 回复了你的帖子《%s》", fromUsername, postTitle),
            Notification.REL_POST, postId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notifyTaskDone(Long userId, String lessonTitle, Long lessonId) {
        notificationRepository.save(new Notification(
            userId, Notification.TYPE_TASK,
            "任务完成",
            String.format("恭喜完成课时《%s》的学习,继续加油!", lessonTitle),
            Notification.REL_LESSON, lessonId));
    }

    // ==================== 私有工具 ====================

    /** 归一化类型:空/ALL=全部,非法值拒绝 */
    private String normalizeType(String type) {
        if (type == null || type.trim().isEmpty() || "ALL".equalsIgnoreCase(type.trim())) {
            return null;
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        boolean ok = Notification.TYPE_SYSTEM.equals(t)
            || Notification.TYPE_REPLY.equals(t)
            || Notification.TYPE_TASK.equals(t)
            || Notification.TYPE_COLLAB_REQUEST.equals(t)
            || Notification.TYPE_COLLAB_RESULT.equals(t);
        if (!ok) {
            throw new IllegalArgumentException("非法消息类型: " + type);
        }
        return t;
    }

    private NotificationDTO toDTO(Notification n) {
        NotificationDTO dto = new NotificationDTO();
        dto.setId(n.getId());
        dto.setType(n.getType());
        dto.setTypeLabel(typeLabel(n.getType()));
        dto.setTitle(n.getTitle());
        dto.setContent(n.getContent());
        dto.setRelatedType(n.getRelatedType());
        dto.setRelatedId(n.getRelatedId());
        dto.setRead(Boolean.TRUE.equals(n.getIsRead()));
        dto.setCreateTime(n.getCreateTime());
        return dto;
    }

    private String typeLabel(String type) {
        switch (type) {
            case Notification.TYPE_SYSTEM: return "系统通知";
            case Notification.TYPE_REPLY:  return "回复通知";
            case Notification.TYPE_TASK:   return "任务通知";
            case Notification.TYPE_COLLAB_REQUEST: return "协作申请";
            case Notification.TYPE_COLLAB_RESULT:  return "协作结果";
            default: return type;
        }
    }
}
