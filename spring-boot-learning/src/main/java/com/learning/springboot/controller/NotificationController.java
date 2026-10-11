package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.NotificationDTO;
import com.learning.springboot.dto.SystemNoticeDTO;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息中心 API(头部铃铛)
 * <p>
 * - GET  /api/notifications              消息分页(可按类型筛)
 * - GET  /api/notifications/recent       最近 8 条(头部面板)
 * - GET  /api/notifications/unread-count 未读数
 * - PUT  /api/notifications/{id}/read    单条已读
 * - PUT  /api/notifications/read-all     全部已读
 * - POST /api/notifications/system       发布系统通知(仅管理员)
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired private NotificationService notificationService;

    /** 消息分页(最新在前);type: SYSTEM/REPLY/TASK,空=全部 */
    @GetMapping
    public ResponseEntity<?> page(@RequestParam(required = false) String type,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  @CurrentUser Long userId) {
        return Result.ok(notificationService.page(userId, type, page, size));
    }

    /** 最近 8 条消息(头部消息中心面板) */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(@CurrentUser Long userId) {
        return Result.ok(notificationService.recent(userId));
    }

    /** 未读数 */
    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(@CurrentUser Long userId) {
        return Result.ok(notificationService.unreadCount(userId));
    }

    /** 单条标记已读 */
    @PutMapping("/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable Long id, @CurrentUser Long userId) {
        notificationService.markRead(userId, id);
        return Result.ok("已标记为已读", null);
    }

    /** 全部标记已读 */
    @PutMapping("/read-all")
    public ResponseEntity<?> markAllRead(@CurrentUser Long userId) {
        int count = notificationService.markAllRead(userId);
        return Result.ok("已全部标记已读", count);
    }

    /** 发布系统通知(推送给全体启用用户) */
    @PostMapping("/system")
    @RequireAdmin
    @OperationLog(module = "消息中心", action = "发布系统通知")
    public ResponseEntity<?> sendSystemNotice(@RequestBody SystemNoticeDTO dto) {
        int count = notificationService.sendSystemNotice(dto.getTitle(), dto.getContent());
        return Result.created("系统通知已推送给 " + count + " 位用户", count);
    }
}
