package com.learning.springboot.repository;

import com.learning.springboot.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 站内消息数据访问层
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 用户消息分页(最新在前) */
    Page<Notification> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);

    /** 用户消息分页(按类型筛选,最新在前) */
    Page<Notification> findByUserIdAndTypeOrderByCreateTimeDesc(Long userId, String type, Pageable pageable);

    /** 未读数 */
    long countByUserIdAndIsReadFalse(Long userId);

    /** 最近 N 条消息(头部消息中心面板) */
    List<Notification> findTop8ByUserIdOrderByCreateTimeDesc(Long userId);

    /**
     * 某用户的某类关联消息（2026-10-22 需求 P：仓库临期提醒去重）。
     *
     * <p>仓库的临期提醒按「批次 id」去重——同一批货只提醒一次，
     * 所以要按 related_type + related_id 找到已提醒过的批次。
     */
    List<Notification> findByUserIdAndRelatedType(Long userId, String relatedType);

    /** 单条标记已读 */
    @Modifying
    @Query("update Notification n set n.isRead = true where n.id = :id and n.userId = :userId and n.isRead = false")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    /** 全部标记已读,返回受影响条数 */
    @Modifying
    @Query("update Notification n set n.isRead = true where n.userId = :userId and n.isRead = false")
    int markAllRead(@Param("userId") Long userId);
}
