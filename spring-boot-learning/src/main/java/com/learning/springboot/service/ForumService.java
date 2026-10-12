package com.learning.springboot.service;

import com.learning.springboot.dto.ForumVO;
import com.learning.springboot.dto.PageResult;

/**
 * 论坛 Service — 用户学习交流
 * <p>
 * 发帖/回帖所有登录用户可用;编辑/删除限作者或管理员。
 */
public interface ForumService {

    /** 帖子分页列表(科目维度 + 关键字,置顶优先;sort: updated/created/hot/likes/favorites/replies/views) */
    PageResult<ForumVO.PostItemVO> pagePosts(Long subjectId, String keyword, String sort, int page, int size, Long currentUserId);

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

    /** 点赞/取消点赞(切换),返回最新互动状态 */
    ForumVO.InteractVO toggleLike(Long postId, Long userId);

    /** 收藏/取消收藏(切换),返回最新互动状态 */
    ForumVO.InteractVO toggleFavorite(Long postId, Long userId);

    /** 转发(计数 +1),返回最新互动状态 */
    ForumVO.InteractVO sharePost(Long postId, Long currentUserId);
}
