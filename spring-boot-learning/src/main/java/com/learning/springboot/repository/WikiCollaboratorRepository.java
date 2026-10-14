package com.learning.springboot.repository;

import com.learning.springboot.entity.WikiCollaborator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WikiCollaboratorRepository extends JpaRepository<WikiCollaborator, Long> {

    /** 同一资源同一用户有效记录(任意状态, 用于查重 / 找回历史) */
    List<WikiCollaborator> findByResourceTypeAndResourceIdAndUserId(String resourceType, Long resourceId, Long userId);

    /** 该用户作为协作者在哪些资源上有权限(用于树里显示「共享给我」) */
    List<WikiCollaborator> findByUserIdAndStatus(Long userId, String status);

    /** 该资源下的所有协作者(所有者用, 用于管理) */
    List<WikiCollaborator> findByResourceTypeAndResourceId(String resourceType, Long resourceId);

    /** 资源所有者的「待我审批」: ownerId 是审批方, status=PENDING */
    List<WikiCollaborator> findByOwnerIdAndStatusOrderByCreateTimeDesc(Long ownerId, String status);

    /** 我作为申请人的所有申请(包含历史) */
    List<WikiCollaborator> findByUserIdOrderByCreateTimeDesc(Long userId);

    /** 单条精确查 (资源+用户+状态) */
    Optional<WikiCollaborator> findFirstByResourceTypeAndResourceIdAndUserIdAndStatus(
        String resourceType, Long resourceId, Long userId, String status);

    /** 资源级全部已通过的协作者(用于查 effective 权限 / 清理) */
    List<WikiCollaborator> findByResourceTypeAndResourceIdAndStatus(
        String resourceType, Long resourceId, String status);

    /** 删除资源(目录/文章)前清理 */
    void deleteByResourceTypeAndResourceId(String resourceType, Long resourceId);
}
