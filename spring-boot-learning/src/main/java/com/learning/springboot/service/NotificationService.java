package com.learning.springboot.service;

import com.learning.springboot.dto.NotificationDTO;
import com.learning.springboot.dto.PageResult;

/**
 * 站内消息(消息中心)服务
 * <p>
 * 职责:
 * - 消息查询(分页/未读数/最近消息)与已读处理
 * - 各业务点的通知产生: 论坛回复、任务完成、系统通知
 */
public interface NotificationService {

    /** 消息分页(最新在前);type 空=全部 */
    PageResult<NotificationDTO> page(Long userId, String type, int page, int size);

    /** 最近 N 条消息(头部消息中心面板) */
    java.util.List<NotificationDTO> recent(Long userId);

    /** 未读数 */
    long unreadCount(Long userId);

    /** 单条标记已读(幂等,只能标自己的) */
    void markRead(Long userId, Long id);

    /** 全部标记已读,返回处理条数 */
    int markAllRead(Long userId);

    /** 发布系统通知: 推送给全体启用用户,返回推送人数 */
    int sendSystemNotice(String title, String content);

    /** 论坛回复通知: 通知楼主(自己回自己不发) */
    void notifyReply(Long postAuthorId, Long fromUserId, String fromUsername, String postTitle, Long postId);

    /** 任务完成通知: 课时学习完成 */
    void notifyTaskDone(Long userId, String lessonTitle, Long lessonId);
}
