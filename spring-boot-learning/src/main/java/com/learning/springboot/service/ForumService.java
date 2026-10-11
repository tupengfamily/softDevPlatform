package com.learning.springboot.service;

import com.learning.springboot.dto.ForumVO;
import com.learning.springboot.dto.PageResult;

/**
 * 论坛 Service — 用户学习交流
 * <p>
 * 发帖/回帖所有登录用户可用;编辑/删除限作者或管理员。
 */
public interface ForumService {

    /** 帖子分页列表(科目维度 + 关键字,置顶优先) */
    PageResult<ForumVO.PostItemVO> pagePosts(Long subjectId, String keyword, int page, int size);

    /** 帖子详情(浏览数 +1) */
    ForumVO.PostDetailVO getPost(Long id, Long currentUserId, boolean admin);

    /** 发帖 */
    ForumVO.PostDetailVO createPost(ForumVO.PostUpsertRequest req, Long userId, String username, boolean admin);

    /** 编辑帖子(作者或管理员) */
    ForumVO.PostDetailVO updatePost(Long id, ForumVO.PostUpsertRequest req, Long userId, boolean admin);

    /** 删除帖子(作者或管理员),级联删除回复 */
    void deletePost(Long id, Long userId, boolean admin);

    /** 帖子回复分页 */
    PageResult<ForumVO.ReplyVO> pageReplies(Long postId, int page, int size, Long currentUserId, boolean admin);

    /** 回复 */
    ForumVO.ReplyVO createReply(Long postId, ForumVO.ReplyCreateRequest req, Long userId, String username);

    /** 删除回复(作者或管理员) */
    void deleteReply(Long id, Long userId, boolean admin);
}
