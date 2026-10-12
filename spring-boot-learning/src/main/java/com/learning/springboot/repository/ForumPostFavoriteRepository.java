package com.learning.springboot.repository;

import com.learning.springboot.entity.ForumPostFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
