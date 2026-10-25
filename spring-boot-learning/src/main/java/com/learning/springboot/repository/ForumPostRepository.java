package com.learning.springboot.repository;

import com.learning.springboot.entity.ForumPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 论坛帖子 Repository
 * <p>
 * 列表检索条件(科目/关键字/置顶)较灵活,使用 JpaSpecificationExecutor 动态拼接。
 */
public interface ForumPostRepository
        extends JpaRepository<ForumPost, Long>, JpaSpecificationExecutor<ForumPost> {

    /**
     * 浏览数 +1（原子）。
     *
     * <p>🔴 2026-10-26 修复 M10：原实现<b>没有</b> {@code flushAutomatically / clearAutomatically}，
     * 导致执行完这条 UPDATE 之后，<b>持久化上下文里还留着旧实体</b>。
     * 调用方接着执行 {@code post.setViewCount(post.getViewCount() + 1); save(post)}，
     * 就把内存里的旧值<b>覆盖写回</b>数据库 —— 并发下浏览数大量丢失。
     *
     * <p>现在加上 {@code clearAutomatically}：执行后持久化上下文被清空，
     * 调用方必须<b>重查</b>实体才能拿到最新值（见 {@code ForumServiceImpl.getPost}）。
     *
     * <p>参考范式：{@code WikiArticleRepository.incrementViewCount}。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ForumPost p SET p.viewCount = COALESCE(p.viewCount,0) + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /**
     * 回复数重算(删除回复后校准)。
     *
     * <p>同样需要 {@code clearAutomatically} —— 否则后续的 {@code save} 会用旧值覆盖。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ForumPost p SET p.replyCount = (SELECT COUNT(r) FROM ForumReply r WHERE r.postId = p.id) WHERE p.id = :id")
    void recalcReplyCount(@Param("id") Long id);

    /**
     * 点赞数原子 +1 / -1（🔴 2026-10-26 修复 M12）。
     *
     * <p>原实现是「查 count → 写回计数」的覆盖写，两个并发点赞会互相覆盖。
     * 现在用数据库层的 {@code SET likeCount = likeCount + 1} 原子更新。
     *
     * @param delta +1 或 -1
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ForumPost p SET p.likeCount = COALESCE(p.likeCount,0) + :delta WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 收藏数原子 +1 / -1（🔴 2026-10-26 修复 M12）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ForumPost p SET p.favoriteCount = COALESCE(p.favoriteCount,0) + :delta WHERE p.id = :id")
    void incrementFavoriteCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 转发数原子 +1（🔴 2026-10-26 修复 M12）。
     *
     * <p>原实现 {@code post.setShareCount(getShareCount()+1); save(post)} 是读改写，
     * 并发下丢计数。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ForumPost p SET p.shareCount = COALESCE(p.shareCount,0) + 1 WHERE p.id = :id")
    void incrementShareCount(@Param("id") Long id);
}
