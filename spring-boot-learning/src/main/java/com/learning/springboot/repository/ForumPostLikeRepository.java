package com.learning.springboot.repository;

import com.learning.springboot.entity.ForumPostLike;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
