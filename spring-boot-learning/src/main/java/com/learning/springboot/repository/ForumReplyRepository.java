package com.learning.springboot.repository;

import com.learning.springboot.entity.ForumReply;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 论坛回复 Repository
 */
public interface ForumReplyRepository extends JpaRepository<ForumReply, Long> {

    /** 帖子下的回复分页(按时间升序) */
    Page<ForumReply> findByPostIdOrderByCreatedAtAsc(Long postId, Pageable pageable);

    /** 帖子下的全部回复(删除帖子时级联) */
    List<ForumReply> findByPostId(Long postId);

    /** 帖子回复数 */
    long countByPostId(Long postId);

    /** 删除帖子的全部回复 */
    void deleteByPostId(Long postId);

    /** 删除某条回复的全部子回复(楼中楼级联) */
    void deleteByParentReplyId(Long parentReplyId);
}
