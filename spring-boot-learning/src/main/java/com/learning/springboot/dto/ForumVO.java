package com.learning.springboot.dto;

import java.time.LocalDateTime;

/**
 * 论坛模块视图对象与请求体
 */
public class ForumVO {

    /** 帖子列表条目(不含正文,列表页轻量) */
    public static class PostItemVO {
        public Long id;
        public Long subjectId;
        public String subjectName;        // 科目名,综合讨论为空
        public String title;
        public String contentType;
        public Long userId;
        public String username;
        public int viewCount;
        public int replyCount;
        public int likeCount;
        public int favoriteCount;
        public int shareCount;
        public boolean liked;             // 当前用户是否已点赞(未登录恒 false)
        public boolean favorited;         // 当前用户是否已收藏
        public boolean pinned;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    /** 帖子详情(含正文) */
    public static class PostDetailVO {
        public Long id;
        public Long subjectId;
        public String subjectName;
        public String title;
        public String content;
        public String contentType;
        public Long userId;
        public String username;
        public int viewCount;
        public int replyCount;
        public int likeCount;
        public int favoriteCount;
        public int shareCount;
        public boolean liked;
        public boolean favorited;
        public boolean pinned;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public boolean editable;          // 作者或管理员
    }

    /** 点赞/收藏切换结果 */
    public static class InteractVO {
        public boolean liked;
        public boolean favorited;
        public int likeCount;
        public int favoriteCount;
        public int shareCount;
    }

    /** 回复条目 */
    public static class ReplyVO {
        public Long id;
        public Long postId;
        public Long userId;
        public String username;
        public String content;
        public String contentType;
        public Long parentReplyId;        // 楼中楼: 父回复 ID(空=一级回复)
        public String replyToUsername;    // 被回复者(展示"回复 @xxx")
        public LocalDateTime createdAt;
        public boolean editable;
    }

    /** 发帖/编辑请求 */
    public static class PostUpsertRequest {
        public Long subjectId;            // 可空=综合讨论
        public String title;
        public String content;
        public String contentType;
        public Boolean pinned;            // 仅管理员可置顶(服务端校验)
    }

    /** 回复请求 */
    public static class ReplyCreateRequest {
        public String content;
        public String contentType;
        public Long parentReplyId;        // 可选: 回复某条回复(楼中楼)
    }
}
