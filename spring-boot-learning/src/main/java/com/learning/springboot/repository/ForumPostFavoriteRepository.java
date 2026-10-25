package com.learning.springboot.repository;

import com.learning.springboot.entity.ForumPostFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 帖子收藏 Repository
 */
public interface ForumPostFavoriteRepository extends JpaRepository<ForumPostFavorite, Long> {

    Optional<ForumPostFavorite> findByPostIdAndUserId(Long postId, Long userId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    void deleteByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);

    /** 用户收藏过的记录(列表回显收藏状态用) */
    List<ForumPostFavorite> findByUserIdAndPostIdIn(Long userId, Collection<Long> postIds);

    /** 帖子被删时清理 */
    void deleteByPostId(Long postId);

    /**
     * 原子插入收藏记录（🔴 2026-10-26 修复 M12）。
     *
     * <p>语义与 {@code ForumPostLikeRepository.insertIgnore} 完全一致：
     * {@code INSERT OR IGNORE} + 唯一索引 {@code (post_id, user_id)}，
     * 返回受影响行数（1=插入成功，0=已存在），不抛异常、不污染事务。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT OR IGNORE INTO t_forum_post_favorite (post_id, user_id, created_at) "
            + "VALUES (:postId, :userId, CURRENT_TIMESTAMP)", nativeQuery = true)
    int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);
}
