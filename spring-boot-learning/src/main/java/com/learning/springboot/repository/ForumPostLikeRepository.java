package com.learning.springboot.repository;

import com.learning.springboot.entity.ForumPostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 帖子点赞 Repository
 */
public interface ForumPostLikeRepository extends JpaRepository<ForumPostLike, Long> {

    Optional<ForumPostLike> findByPostIdAndUserId(Long postId, Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    void deleteByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);

    /** 用户点赞过的记录(列表回显点赞状态用) */
    List<ForumPostLike> findByUserIdAndPostIdIn(Long userId, Collection<Long> postIds);

    /** 帖子被删时清理 */
    void deleteByPostId(Long postId);

    /**
     * 原子插入点赞记录（🔴 2026-10-26 修复 M12）。
     *
     * <p>用 SQLite 的 {@code INSERT OR IGNORE} + 唯一索引 {@code (post_id, user_id)}：
     * <ul>
     *   <li>并发同用户双击 → 只有一条真正插入，另一条被静默忽略；</li>
     *   <li>返回受影响行数（1=插入成功，0=已存在）→ 调用方据此决定要不要加计数，
     *       <b>不需要捕获异常</b>，也就不会把事务标记为 rollback-only。</li>
     * </ul>
     *
     * <p>唯一索引由 {@code ForumInteractSchemaPatch} 在启动时补建
     * （老库的 {@code hibernate.ddl-auto: update} 不会创建它，实测确认）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT OR IGNORE INTO t_forum_post_like (post_id, user_id, created_at) "
            + "VALUES (:postId, :userId, CURRENT_TIMESTAMP)", nativeQuery = true)
    int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);
}
