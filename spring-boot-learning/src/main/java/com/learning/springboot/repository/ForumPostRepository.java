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

    /** 浏览数 +1 */
    @Modifying
    @Query("UPDATE ForumPost p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /** 回复数重算(删除回复后校准) */
    @Modifying
    @Query("UPDATE ForumPost p SET p.replyCount = (SELECT COUNT(r) FROM ForumReply r WHERE r.postId = p.id) WHERE p.id = :id")
    void recalcReplyCount(@Param("id") Long id);
}
